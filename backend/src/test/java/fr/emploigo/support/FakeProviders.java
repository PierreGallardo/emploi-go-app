package fr.emploigo.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The external providers of the integration tests, on one local server started once per JVM: the
 * email channel ({@code /emails}) and Google ({@link FakeGoogle}). {@link IntegrationTest} calls
 * {@link #reset()} before every test.
 */
public final class FakeProviders {
    private static final HttpServer SERVER = start();

    /** Calls received on {@code /emails}, every attempt included. */
    public static final AtomicInteger MAIL_CALLS = new AtomicInteger();

    /** Distinct messages, by {@code Idempotency-Key}. */
    public static final AtomicInteger MAIL_SENDS = new AtomicInteger();

    /** First payload received for each {@code Idempotency-Key}. */
    public static final Map<String, String> MAIL_PAYLOADS = new ConcurrentHashMap<>();

    public static volatile int mailStatus = 200;

    /**
     * The answer of {@code /emails}, held back once the message was recorded: accepted, but not yet
     * confirmed.
     */
    public static final Stall MAIL_RESPONSE = new Stall();

    /**
     * The body of the answer of {@code /emails}, held back after its headers were sent: the delay
     * of a request ends when the headers arrive, so only a bound on the whole exchange ends this.
     */
    public static final Stall MAIL_BODY = new Stall();

    private FakeProviders() {}

    public static String origin() {
        return "http://127.0.0.1:" + SERVER.getAddress().getPort();
    }

    public static void reset() {
        mailStatus = 200;
        MAIL_RESPONSE.reset();
        MAIL_BODY.reset();
        MAIL_PAYLOADS.clear();
        MAIL_SENDS.set(0);
        MAIL_CALLS.set(0);
        FakeGoogle.reset();
    }

    private static HttpServer start() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            server.createContext("/emails", FakeProviders::mail);
            FakeGoogle.register(server);
            server.start();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop(0)));
            return server;
        } catch (IOException error) {
            throw new ExceptionInInitializerError("Fournisseurs de test indisponibles.");
        }
    }

    private static void mail(HttpExchange exchange) throws IOException {
        String payload =
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String key = exchange.getRequestHeaders().getFirst("Idempotency-Key");
        MAIL_CALLS.incrementAndGet();
        String prior = key == null ? null : MAIL_PAYLOADS.putIfAbsent(key, payload);
        if (key == null || prior == null) MAIL_SENDS.incrementAndGet();
        MAIL_RESPONSE.pass();
        int status = prior != null && !prior.equals(payload) ? 409 : mailStatus;
        String body =
                status == 409
                        ? "{\"name\":\"invalid_idempotent_request\"}"
                        : status == 403
                                ? "{\"message\":\"Only owner-fixture@example.invalid is allowed by resend.dev private-provider-detail\"}"
                                : "{\"id\":\"fixture-message\"}";
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().flush();
        MAIL_BODY.pass();
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    static void respond(HttpExchange exchange, int status, String body, String contentType)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        if (contentType != null) exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
