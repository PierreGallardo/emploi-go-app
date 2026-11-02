package fr.emploigo.auth.infrastructure.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import fr.emploigo.auth.application.mail.AuthenticationMailSender.Result;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.mock.env.MockEnvironment;

import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * How the sender translates the answers of the provider into a result, against a local server that
 * answers what the test chose: no call leaves the machine.
 */
class ResendAuthenticationMailSenderTest {
    private static final String ACCEPTED_BODY = "{\"id\":\"fixture-message\"}";

    private final CountDownLatch release = new CountDownLatch(1);
    private final AtomicInteger requests = new AtomicInteger();
    private volatile int status = 200;
    private volatile String body = ACCEPTED_BODY;
    private volatile boolean hold;

    /** Answers with its headers and a start of the body, then holds back the rest. */
    private volatile boolean stallBody;

    private HttpServer server;
    private ResendAuthenticationMailSender sender;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/emails", this::answer);
        server.start();
        sender = senderOf(configuration(true));
    }

    @AfterEach
    void stopServer() {
        release.countDown();
        server.stop(0);
    }

    private static ResendAuthenticationMailSender senderOf(
            AuthenticationMailConfiguration configuration) {
        return new ResendAuthenticationMailSender(configuration, JsonMapper.builder().build());
    }

    private AuthenticationMailConfiguration configuration(boolean enabled) {
        var environment =
                new MockEnvironment()
                        .withProperty(
                                "emploi-go.registration.mail-mode", enabled ? "local" : "disabled")
                        .withProperty(
                                "emploi-go.registration.local-mail-url",
                                "http://127.0.0.1:" + server.getAddress().getPort() + "/emails")
                        .withProperty(
                                "emploi-go.registration.token-key",
                                Base64.getEncoder().encodeToString(new byte[32]))
                        .withProperty("emploi-go.registration.mail-connect-timeout", "300ms")
                        .withProperty("emploi-go.registration.mail-response-timeout", "300ms");
        environment.setActiveProfiles("test");
        return new AuthenticationMailConfiguration(environment);
    }

    private void answer(HttpExchange exchange) throws IOException {
        requests.incrementAndGet();
        exchange.getRequestBody().readAllBytes();
        if (hold) await();
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        int sent = stallBody ? 5 : bytes.length;
        exchange.getResponseBody().write(bytes, 0, sent);
        exchange.getResponseBody().flush();
        if (stallBody) await();
        exchange.getResponseBody().write(bytes, sent, bytes.length - sent);
        exchange.close();
    }

    private void await() {
        try {
            release.await();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }

    private Result send() {
        return sender.send(
                "registration-mail/fixture",
                "jeune@example.invalid",
                "noreply@example.invalid",
                "Sujet",
                "Texte");
    }

    @Test
    void anAnswerWithAMessageIdentifierIsSent() {
        status = 200;
        body = ACCEPTED_BODY;

        assertThat(send()).as("result").isEqualTo(Result.SENT);
    }

    static Stream<Arguments> refusals() {
        return Stream.of(
                argumentSet("200 sans identifiant", 200, "{}"),
                argumentSet("200 avec un identifiant vide", 200, "{\"id\":\" \"}"),
                argumentSet("200 avec un identifiant qui n'est pas du texte", 200, "{\"id\":42}"),
                argumentSet("400", 400, "{\"name\":\"validation_error\"}"),
                argumentSet("401", 401, "{\"name\":\"missing_api_key\"}"),
                argumentSet("403", 403, "{\"name\":\"invalid_from_address\"}"),
                argumentSet("422", 422, "{\"name\":\"invalid_parameter\"}"),
                argumentSet(
                        "409 requête d'idempotence invalide",
                        409,
                        "{\"name\":\"invalid_idempotent_request\"}"));
    }

    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("refusals")
    void anAnswerThatRefusesTheMessageFailsTheSend(int answeredStatus, String answeredBody) {
        status = answeredStatus;
        body = answeredBody;

        assertThat(send()).as("result").isEqualTo(Result.FAILED);
    }

    static Stream<Arguments> answersToRepeat() {
        return Stream.of(
                argumentSet("429", 429, "{\"name\":\"rate_limit_exceeded\"}"),
                argumentSet("500", 500, "{\"name\":\"internal_server_error\"}"),
                argumentSet("503", 503, "<html>Service Unavailable</html>"),
                argumentSet(
                        "409 requêtes d'idempotence concurrentes",
                        409,
                        "{\"name\":\"concurrent_idempotent_requests\"}"),
                argumentSet("200 avec un corps illisible", 200, "<html>Maintenance</html>"));
    }

    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("answersToRepeat")
    void anAnswerThatLeavesTheOutcomeUnknownIsUncertain(int answeredStatus, String answeredBody) {
        status = answeredStatus;
        body = answeredBody;

        assertThat(send()).as("result").isEqualTo(Result.UNCERTAIN);
    }

    @Test
    @Timeout(10)
    void anAnswerAfterTheDelayIsUncertain() {
        hold = true;

        assertThat(send()).as("result").isEqualTo(Result.UNCERTAIN);
    }

    @Test
    @Timeout(10)
    void aBodyThatStopsArrivingIsUncertain() {
        stallBody = true;

        assertThat(send()).as("result").isEqualTo(Result.UNCERTAIN);
    }

    @Test
    void aRefusedConnectionIsUncertain() {
        server.stop(0);

        assertThat(send()).as("result").isEqualTo(Result.UNCERTAIN);
    }

    @Test
    void aDisabledChannelFailsTheSendWithoutCallingTheProvider() {
        sender = senderOf(configuration(false));

        Result result = send();

        assertThat(result).as("result").isEqualTo(Result.FAILED);
        assertThat(requests.get()).as("provider calls").isZero();
    }
}
