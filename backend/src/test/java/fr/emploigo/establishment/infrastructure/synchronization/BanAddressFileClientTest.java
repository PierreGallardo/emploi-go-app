package fr.emploigo.establishment.infrastructure.synchronization;

import static org.assertj.core.api.Assertions.assertThat;

import fr.emploigo.establishment.application.synchronization.AddressFileClient.Result;
import fr.emploigo.establishment.domain.Address;
import fr.emploigo.establishment.domain.HouseNumber;
import fr.emploigo.establishment.domain.Position;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;

/** How the client reads the file of the Base Adresse Nationale served by a local server. */
class BanAddressFileClientTest {
    private static final Map<String, String> LAST_MODIFIED =
            Map.of("Last-Modified", "Fri, 23 Oct 2026 05:01:54 GMT");
    private static final Set<String> METROPOLIS = Set.of("21231");
    private static final String FILE =
            """
            id;id_fantoir;numero;rep;nom_voie;code_postal;code_insee;nom_commune;x;y;lon;lat
            21231_0001_00001;;1;;Rue Fictive;21000;21231;Dijon;0;0;5.04;47.32
            21231_0001_00002;;2;BIS;Rue Fictive;21000;21231;Dijon;0;0;5.0401;47.3201
            21231_0002_99999;;99999;;Lieu-dit Fictif;21000;21231;Dijon;0;0;5.05;47.33
            21231_0003_00004;;4;;;21000;21231;Dijon;0;0;5.05;47.33
            21231_0003_00005;;5;;Rue Sans Position;21000;21231;Dijon;0;0;;
            21003_0001_00001;;1;;Rue Fictive;21121;21003;Ahuy;0;0;5.02;47.36
            """;

    private FakeDatasetServer server;
    private BanAddressFileClient client;

    @BeforeEach
    void startServer() throws IOException {
        server = new FakeDatasetServer();
        client =
                new BanAddressFileClient(
                        server.url("/adresses-21.csv.gz"),
                        server.downloader(),
                        UnpackedLimits.OPEN_DATA);
    }

    @AfterEach
    void stopServer() {
        server.close();
    }

    @Test
    void addressesAreTheNumbersOfTheCommunesAskedForWithAStreetAndAPosition() {
        server.answer(200, LAST_MODIFIED, gzip(FILE));

        Result result = client.fetch(METROPOLIS, Optional.empty());

        assertThat(result)
                .isEqualTo(
                        new Result.Published(
                                Instant.parse("2026-10-23T05:01:54Z"),
                                List.of(
                                        new Address(
                                                "21231",
                                                "Rue Fictive",
                                                new HouseNumber(
                                                        1, null, new Position(5.04, 47.32))),
                                        new Address(
                                                "21231",
                                                "Rue Fictive",
                                                new HouseNumber(
                                                        2,
                                                        "bis",
                                                        new Position(5.0401, 47.3201))))));
    }

    static Stream<String> rowsNoAddressCanBeMadeOf() {
        return Stream.of(
                "21231_0004_00001;;1;bis2;Rue Fictive;21000;21231;Dijon;0;0;5.04;47.32",
                "21231_0004_00000;;0;;Rue Fictive;21000;21231;Dijon;0;0;5.04;47.32",
                "21231_0004_00001;;1;;" + "R".repeat(201) + ";21000;21231;Dijon;0;0;5.04;47.32");
    }

    @ParameterizedTest
    @MethodSource("rowsNoAddressCanBeMadeOf")
    void lineThatMakesNoAddressIsLeftOut(String row) {
        server.answer(
                200,
                LAST_MODIFIED,
                gzip(FILE.lines().findFirst().orElseThrow() + "\n" + row + "\n"));

        Result result = client.fetch(METROPOLIS, Optional.empty());

        assertThat(result)
                .isInstanceOfSatisfying(
                        Result.Published.class,
                        published -> assertThat(published.addresses()).isEmpty());
    }

    @Test
    void truncatedFileIsUnavailable() {
        byte[] whole = gzip(FILE);
        server.answer(200, LAST_MODIFIED, Arrays.copyOf(whole, whole.length / 2));

        Result result = client.fetch(METROPOLIS, Optional.empty());

        assertThat(result).isInstanceOf(Result.Unavailable.class);
    }

    @Test
    void fileThatUnpacksBeyondTheLimitIsUnavailable() {
        server.answer(200, LAST_MODIFIED, gzip(FILE));
        var limited =
                new BanAddressFileClient(
                        server.url("/adresses-21.csv.gz"),
                        server.downloader(),
                        new UnpackedLimits(FILE.length() - 1, 1024, 100));

        Result result = limited.fetch(METROPOLIS, Optional.empty());

        assertThat(result).isInstanceOf(Result.Unavailable.class);
    }

    @Test
    void fileNotPublishedAgainIsUnchanged() {
        server.answer(304, Map.of(), new byte[0]);

        Result result =
                client.fetch(METROPOLIS, Optional.of(Instant.parse("2026-10-23T05:01:54Z")));

        assertThat(result).isEqualTo(new Result.Unchanged());
    }

    private static byte[] gzip(String text) {
        var bytes = new ByteArrayOutputStream();
        try (var gzip = new GZIPOutputStream(bytes)) {
            gzip.write(text.getBytes(StandardCharsets.UTF_8));
        } catch (IOException error) {
            throw new IllegalStateException(error);
        }
        return bytes.toByteArray();
    }
}
