package fr.emploigo.establishment.infrastructure.synchronization;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

/**
 * A local server that answers what the test queued, then holds every other request until it stops:
 * no download leaves the machine.
 */
final class FakeDatasetServer implements AutoCloseable {
    static final Instant NOW = Instant.parse("2026-10-25T03:00:00Z");
    static final Duration TIMEOUT = Duration.ofSeconds(2);

    record Answer(int status, Map<String, String> headers, byte[] body) {}

    private final ConcurrentLinkedQueue<Answer> answers = new ConcurrentLinkedQueue<>();
    private final List<Headers> requests = new CopyOnWriteArrayList<>();
    private final CountDownLatch release = new CountDownLatch(1);
    private final HttpServer server;

    FakeDatasetServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", this::answer);
        server.start();
    }

    URI url(String path) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
    }

    FakeDatasetServer answer(int status, Map<String, String> headers, byte[] body) {
        answers.add(new Answer(status, headers, body));
        return this;
    }

    /** The headers of each request received, in order. */
    List<Headers> requests() {
        return requests;
    }

    /** A downloader of the files of this server, at most {@code maxBytes} each. */
    FileDownloader downloader(int maxBytes) {
        return new FileDownloader(TIMEOUT, TIMEOUT, maxBytes, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    FileDownloader downloader() {
        return downloader(1024 * 1024);
    }

    @Override
    public void close() {
        release.countDown();
        server.stop(0);
    }

    private void answer(HttpExchange exchange) throws IOException {
        requests.add(exchange.getRequestHeaders());
        Answer answer = answers.poll();
        if (answer == null) {
            try {
                release.await();
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
            return;
        }
        answer.headers().forEach((name, value) -> exchange.getResponseHeaders().add(name, value));
        exchange.sendResponseHeaders(
                answer.status(), answer.body().length == 0 ? -1 : answer.body().length);
        exchange.getResponseBody().write(answer.body());
        exchange.close();
    }
}
