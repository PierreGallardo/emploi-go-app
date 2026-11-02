package fr.emploigo.establishment.infrastructure.synchronization;

import static org.assertj.core.api.Assertions.assertThat;

import fr.emploigo.establishment.application.synchronization.TransitFeedClient.Result;
import fr.emploigo.establishment.domain.Position;
import fr.emploigo.establishment.domain.TransitLine;
import fr.emploigo.establishment.domain.TransitMode;
import fr.emploigo.establishment.domain.TransitPlatform;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** How the client reads a GTFS archive served by a local server. */
class GtfsTransitFeedClientTest {
    private static final Map<String, String> LAST_MODIFIED =
            Map.of("Last-Modified", "Fri, 23 Oct 2026 09:34:39 GMT");
    private static final TransitLine T1 = new TransitLine("T1", TransitMode.TRAM);
    private static final Map<String, String> FEED = new LinkedHashMap<>();

    static {
        // First in the archive: the client does not depend on the order of the tables.
        FEED.put(
                "stop_times.txt",
                """
                trip_id,arrival_time,departure_time,stop_id,stop_sequence
                T1-A,06:20:00,06:20:00,4-1,1
                T1-R,06:30:00,06:30:00,4-1,9
                10-A,06:21:00,06:21:00,4-1,1
                10-A,06:25:00,06:25:00,4-3,2
                """);
        FEED.put(
                "routes.txt",
                """
                route_id,agency_id,route_short_name,route_long_name,route_type,route_color
                4-T1,KD,T1,DIJON Gare <> QUETIGNY,0,AB0672
                4-10,KD,,Navette fictive,3,
                4-99,KD,99,Services spéciaux,3,000000
                """);
        FEED.put(
                "trips.txt",
                """
                route_id,service_id,trip_id
                4-T1,S1,T1-A
                4-T1,S1,T1-R
                4-10,S1,10-A
                """);
        FEED.put(
                "stops.txt",
                """
                stop_id,stop_code,stop_name,stop_lat,stop_lon
                4-1,1,Darcy,47.3229,5.0346
                4-2,2,Arrêt sans passage,47.3300,5.0400
                4-3,3,Arrêt sans position,,
                """);
    }

    private FakeDatasetServer server;
    private GtfsTransitFeedClient client;

    @BeforeEach
    void startServer() throws IOException {
        server = new FakeDatasetServer();
        client =
                new GtfsTransitFeedClient(
                        server.url("/gtfs.zip"), server.downloader(), UnpackedLimits.OPEN_DATA);
    }

    @AfterEach
    void stopServer() {
        server.close();
    }

    @Test
    void platformsAreTheStopsWithAPositionThatATripCallsAtWithTheLinesOfTheirTrips() {
        server.answer(200, LAST_MODIFIED, zip(FEED));

        Result result = client.fetch(Optional.empty());

        assertThat(result)
                .isEqualTo(
                        new Result.Published(
                                Instant.parse("2026-10-23T09:34:39Z"),
                                List.of(
                                        new TransitPlatform(
                                                "Darcy",
                                                new Position(5.0346, 47.3229),
                                                Set.of(
                                                        T1,
                                                        new TransitLine(
                                                                "Navette fictive",
                                                                TransitMode.BUS))))));
    }

    static Stream<Arguments> namesTheMapCannotShow() {
        return Stream.of(
                Arguments.of("Darcy", "A".repeat(101)),
                Arguments.of("T1,DIJON", "T".repeat(21) + ",DIJON"));
    }

    @ParameterizedTest
    @MethodSource("namesTheMapCannotShow")
    void stopOrLineWithTooLongANameIsLeftOut(String name, String longer) {
        server.answer(200, LAST_MODIFIED, zip(FEED, name, longer));

        Result result = client.fetch(Optional.empty());

        assertThat(result)
                .isInstanceOfSatisfying(
                        Result.Published.class,
                        published ->
                                assertThat(published.platforms())
                                        .noneSatisfy(
                                                platform ->
                                                        assertThat(platform.lines()).contains(T1)));
    }

    @Test
    void feedNotPublishedAgainIsUnchanged() {
        server.answer(304, Map.of(), new byte[0]);

        Result result = client.fetch(Optional.of(Instant.parse("2026-10-23T09:34:39Z")));

        assertThat(result).isEqualTo(new Result.Unchanged());
    }

    @Test
    void archiveWithoutOneOfItsTablesIsUnavailable() {
        Map<String, String> incomplete = new LinkedHashMap<>(FEED);
        incomplete.remove("stop_times.txt");
        server.answer(200, LAST_MODIFIED, zip(incomplete));

        Result result = client.fetch(Optional.empty());

        assertThat(result).isInstanceOf(Result.Unavailable.class);
    }

    @Test
    void archiveThatUnpacksBeyondTheLimitBeforeItsLastTableIsUnavailable() {
        server.answer(200, LAST_MODIFIED, zip(FEED));
        // The tables read first fit the limit; the entries the last one is found behind do not.
        long unpacked = FEED.values().stream().mapToLong(String::length).sum();
        var limited =
                new GtfsTransitFeedClient(
                        server.url("/gtfs.zip"),
                        server.downloader(),
                        new UnpackedLimits(unpacked - 1, 1024, 100));

        Result result = limited.fetch(Optional.empty());

        assertThat(result).isInstanceOf(Result.Unavailable.class);
    }

    @Test
    void truncatedArchiveIsUnavailable() {
        byte[] whole = zip(FEED);
        server.answer(200, LAST_MODIFIED, Arrays.copyOf(whole, whole.length / 2));

        Result result = client.fetch(Optional.empty());

        assertThat(result).isInstanceOf(Result.Unavailable.class);
    }

    @Test
    void failedDownloadIsUnavailable() {
        server.answer(503, Map.of(), new byte[0]);

        Result result = client.fetch(Optional.empty());

        assertThat(result).isEqualTo(new Result.Unavailable("statut 503"));
    }

    /** The archive of the tables, with {@code text} replaced by {@code replacement} in each. */
    private static byte[] zip(Map<String, String> tables, String text, String replacement) {
        Map<String, String> changed = new LinkedHashMap<>();
        tables.forEach((name, table) -> changed.put(name, table.replace(text, replacement)));
        return zip(changed);
    }

    private static byte[] zip(Map<String, String> tables) {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            for (var table : tables.entrySet()) {
                zip.putNextEntry(new ZipEntry(table.getKey()));
                zip.write(table.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        } catch (IOException error) {
            throw new IllegalStateException(error);
        }
        return bytes.toByteArray();
    }
}
