package fr.emploigo.establishment.infrastructure.synchronization;

import static fr.emploigo.establishment.infrastructure.synchronization.FakeDatasetServer.NOW;

import static org.assertj.core.api.Assertions.assertThat;

import fr.emploigo.establishment.infrastructure.synchronization.FileDownloader.Download;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

class FileDownloaderTest {
    private static final Instant PUBLISHED = Instant.parse("2026-10-23T09:34:39Z");
    private static final String LAST_MODIFIED = "Fri, 23 Oct 2026 09:34:39 GMT";
    private static final byte[] CONTENT = "contenu".getBytes(StandardCharsets.UTF_8);

    private FakeDatasetServer server;
    private FileDownloader downloader;

    @BeforeEach
    void startServer() throws IOException {
        server = new FakeDatasetServer();
        downloader = server.downloader();
    }

    @AfterEach
    void stopServer() {
        server.close();
    }

    @Test
    void fileIsPublishedAtItsLastModificationAndAskedForUnderTheNameOfTheService() {
        server.answer(200, Map.of("Last-Modified", LAST_MODIFIED), CONTENT);

        Download download = downloader.download(server.url("/fichier"), Optional.empty());

        assertThat(download)
                .isInstanceOfSatisfying(
                        Download.File.class,
                        file -> {
                            assertThat(file.publishedAt()).isEqualTo(PUBLISHED);
                            assertThat(file.body()).isEqualTo(CONTENT);
                        });
        assertThat(server.requests().getFirst().getFirst("User-Agent"))
                .isEqualTo("Emploi Go (Mission Locale de Dijon)");
    }

    @Test
    void knownPublicationIsSentAndANotModifiedAnswerIsNoFile() {
        server.answer(304, Map.of(), new byte[0]);

        Download download = downloader.download(server.url("/fichier"), Optional.of(PUBLISHED));

        assertThat(download).isInstanceOf(Download.NotModified.class);
        assertThat(server.requests().getFirst().getFirst("If-Modified-Since"))
                .isEqualTo(LAST_MODIFIED);
    }

    @Test
    void fileLargerThanTheLimitIsAFailure() {
        server.answer(200, Map.of("Last-Modified", LAST_MODIFIED), CONTENT);

        Download download =
                server.downloader(CONTENT.length - 1)
                        .download(server.url("/fichier"), Optional.empty());

        assertThat(download)
                .isEqualTo(
                        new Download.Failed(
                                "pas de réponse complète (délai, réseau ou fichier de plus de 0"
                                        + " Mio)"));
    }

    @Test
    void downloadOfAStoppingWorkerIsAFailureThatKeepsTheInterruption() {
        Thread.currentThread().interrupt();

        Download download = downloader.download(server.url("/fichier"), Optional.empty());

        assertThat(download).isEqualTo(new Download.Failed("interrompu"));
        assertThat(Thread.interrupted()).isTrue();
    }

    @Test
    void knownPublicationOnADayOfOneDigitIsSentWithTwoDigits() {
        server.answer(304, Map.of(), new byte[0]);

        downloader.download(
                server.url("/fichier"), Optional.of(Instant.parse("2026-11-03T08:00:00Z")));

        assertThat(server.requests().getFirst().getFirst("If-Modified-Since"))
                .isEqualTo("Tue, 03 Nov 2026 08:00:00 GMT");
    }

    @Test
    void fileOfAnOlderPublicationIsAFile() {
        server.answer(200, Map.of("Last-Modified", LAST_MODIFIED), CONTENT);

        Download download =
                downloader.download(
                        server.url("/fichier"), Optional.of(Instant.parse("2026-10-30T00:00:00Z")));

        assertThat(download).isInstanceOf(Download.File.class);
    }

    @Test
    void sameFileSentAgainIsNotModified() {
        server.answer(200, Map.of("Last-Modified", LAST_MODIFIED), CONTENT);

        Download download = downloader.download(server.url("/fichier"), Optional.of(PUBLISHED));

        assertThat(download).isInstanceOf(Download.NotModified.class);
    }

    @Test
    void fileWithoutLastModificationIsPublishedAtTheDownload() {
        server.answer(200, Map.of(), CONTENT);

        Download download = downloader.download(server.url("/fichier"), Optional.empty());

        assertThat(download)
                .isInstanceOfSatisfying(
                        Download.File.class, file -> assertThat(file.publishedAt()).isEqualTo(NOW));
    }

    @Test
    void stableAddressIsFollowedToTheCurrentFile() {
        server.answer(302, Map.of("Location", server.url("/courant").toString()), new byte[0])
                .answer(200, Map.of("Last-Modified", LAST_MODIFIED), CONTENT);

        Download download = downloader.download(server.url("/stable"), Optional.empty());

        assertThat(download).isInstanceOf(Download.File.class);
    }

    @Test
    void errorStatusIsAFailure() {
        server.answer(503, Map.of(), CONTENT);

        Download download = downloader.download(server.url("/fichier"), Optional.empty());

        assertThat(download).isEqualTo(new Download.Failed("statut 503"));
    }

    @Test
    void answerThatDoesNotComeWithinTheTimeoutsIsAFailure() {
        Download download = downloader.download(server.url("/fichier"), Optional.empty());

        assertThat(download).isInstanceOf(Download.Failed.class);
    }
}
