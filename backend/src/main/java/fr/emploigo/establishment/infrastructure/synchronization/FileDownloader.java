package fr.emploigo.establishment.infrastructure.synchronization;

import fr.emploigo.config.BoundedHttpExchange;
import fr.emploigo.config.ExternalHttpHeaders;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Downloads a whole open data file in one attempt, bounded in time and size: the daily task tries
 * again later. Its publication is the {@code Last-Modified} date of the answer.
 */
final class FileDownloader {
    private static final Logger LOG = LoggerFactory.getLogger(FileDownloader.class);

    sealed interface Download {
        record File(Instant publishedAt, byte[] body) implements Download {}

        /** The file is still the one of the known publication. */
        record NotModified() implements Download {}

        record Failed(String cause) implements Download {}
    }

    private final Duration downloadTimeout;
    private final Duration bound;
    private final int maxBytes;
    private final Clock clock;
    private final HttpClient client;

    FileDownloader(Duration connectTimeout, Duration downloadTimeout, int maxBytes, Clock clock) {
        this.downloadTimeout = downloadTimeout;
        this.bound = connectTimeout.plus(downloadTimeout);
        this.maxBytes = maxBytes;
        this.clock = clock;
        // data.gouv.fr publishes a stable address that redirects to the current file.
        this.client =
                HttpClient.newBuilder()
                        .connectTimeout(connectTimeout)
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .build();
    }

    Download download(URI url, Optional<Instant> knownPublication) {
        var request =
                HttpRequest.newBuilder(url)
                        .timeout(downloadTimeout)
                        .header("User-Agent", ExternalHttpHeaders.USER_AGENT)
                        .GET();
        knownPublication.ifPresent(
                known -> request.header("If-Modified-Since", ExternalHttpHeaders.date(known)));
        Optional<HttpResponse<byte[]>> answer;
        try {
            answer =
                    BoundedHttpExchange.send(
                            client,
                            request.build(),
                            HttpResponse.BodyHandlers.limiting(
                                    HttpResponse.BodyHandlers.ofByteArray(), maxBytes),
                            bound);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return new Download.Failed("interrompu");
        }
        if (answer.isEmpty())
            return new Download.Failed(
                    "pas de réponse complète (délai, réseau ou fichier de plus de "
                            + maxBytes / (1024 * 1024)
                            + " Mio)");
        HttpResponse<byte[]> response = answer.get();
        // The stable address redirects: the host that served the file is what an audit needs.
        LOG.info(
                "Téléchargement de {} : statut {}, servi par {}",
                url.getHost(),
                response.statusCode(),
                response.uri().getHost());
        if (response.statusCode() == 304) return new Download.NotModified();
        if (response.statusCode() != 200)
            return new Download.Failed("statut " + response.statusCode());
        Instant publishedAt =
                response.headers()
                        .firstValue("Last-Modified")
                        .flatMap(ExternalHttpHeaders::instant)
                        .orElseGet(clock::instant);
        // A server that ignores If-Modified-Since sends the same file again.
        if (knownPublication.filter(publishedAt::equals).isPresent())
            return new Download.NotModified();
        return new Download.File(publishedAt, response.body());
    }
}
