package fr.emploigo.establishment.infrastructure.synchronization;

import static org.assertj.core.api.Assertions.assertThat;

import fr.emploigo.establishment.application.synchronization.TrainingOrganizationFileClient.Result;
import fr.emploigo.establishment.domain.Siret;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** How the client reads the public list of the training organisations served by a local server. */
class PublicTrainingOrganizationFileClientTest {
    private static final Map<String, String> LAST_MODIFIED =
            Map.of("Last-Modified", "Fri, 23 Oct 2026 05:01:54 GMT");
    private static final String HEADER =
            "numeroDeclarationActivite;denomination;siren;siretEtablissementDeclarant;"
                    + "adressePhysiqueOrganismeFormation.ville\n";
    private static final String FILE =
            HEADER
                    + "\"11111111111\";\"ORGANISME FICTIF\";\"123456789\";\"12345678900012\";\"DIJON\"\n"
                    + "\"22222222222\";\"AUTRE FICTIF\";\"223456789\";\"22345678900012\";\"TALANT\"\n"
                    + "\"22222222223\";\"DOUBLON FICTIF\";\"223456789\";\"22345678900012\";\"TALANT\"\n";

    private FakeDatasetServer server;
    private PublicTrainingOrganizationFileClient client;

    @BeforeEach
    void startServer() throws IOException {
        server = new FakeDatasetServer();
        client =
                new PublicTrainingOrganizationFileClient(
                        server.url("/of.csv"), server.downloader(), UnpackedLimits.OPEN_DATA);
    }

    @AfterEach
    void stopServer() {
        server.close();
    }

    @Test
    void organizationsAreTheEstablishmentsThatDeclaredThemselves() {
        server.answer(200, LAST_MODIFIED, bytes(FILE));

        Result result = client.fetch(Optional.empty());

        assertThat(result)
                .isEqualTo(
                        new Result.Published(
                                Instant.parse("2026-10-23T05:01:54Z"),
                                Set.of(
                                        new Siret("12345678900012"),
                                        new Siret("22345678900012"))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "1234", "1234567890001X"})
    void lineWithoutAValidSiretIsLeftOut(String siret) {
        server.answer(
                200,
                LAST_MODIFIED,
                bytes(HEADER + "\"1\";\"ORGANISME FICTIF\";\"123456789\";\"" + siret + "\";\"X\"\n"));

        Result result = client.fetch(Optional.empty());

        assertThat(result)
                .isInstanceOfSatisfying(
                        Result.Published.class,
                        published -> assertThat(published.sirets()).isEmpty());
    }

    @Test
    void fileThatUnpacksBeyondTheLimitIsUnavailable() {
        server.answer(200, LAST_MODIFIED, bytes(FILE));
        var limited =
                new PublicTrainingOrganizationFileClient(
                        server.url("/of.csv"),
                        server.downloader(),
                        new UnpackedLimits(FILE.length() - 1, 1024, 100));

        Result result = limited.fetch(Optional.empty());

        assertThat(result).isInstanceOf(Result.Unavailable.class);
    }

    @Test
    void fileNotPublishedAgainIsUnchanged() {
        server.answer(304, Map.of(), new byte[0]);

        Result result = client.fetch(Optional.of(Instant.parse("2026-10-23T05:01:54Z")));

        assertThat(result).isEqualTo(new Result.Unchanged());
        assertThat(server.requests().getFirst().getFirst("If-Modified-Since"))
                .isEqualTo("Fri, 23 Oct 2026 05:01:54 GMT");
    }

    @Test
    void refusedRequestIsUnavailable() {
        server.answer(503, Map.of(), new byte[0]);

        Result result = client.fetch(Optional.empty());

        assertThat(result).isEqualTo(new Result.Unavailable("statut 503"));
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
