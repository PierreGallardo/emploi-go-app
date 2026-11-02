package fr.emploigo.auth.api.quota;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import ch.qos.logback.classic.Level;

import fr.emploigo.auth.api.GoogleBrowser;
import fr.emploigo.auth.application.AuthenticationIntegrationTest;
import fr.emploigo.auth.application.account.AccountRepository;
import fr.emploigo.auth.application.quota.FailureMode;
import fr.emploigo.auth.application.quota.QuotaEnforcer;
import fr.emploigo.auth.application.quota.QuotaScope;
import fr.emploigo.auth.domain.Account;
import fr.emploigo.auth.domain.TestPasswords;
import fr.emploigo.support.Containers;
import fr.emploigo.support.FakeGoogle;
import fr.emploigo.support.LogCapture;
import fr.emploigo.support.MailCounters;

import io.micrometer.core.instrument.MeterRegistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * Every action of the quota while Redis does not answer: the actions that send an email or check a
 * password are refused with a 503; the others run, with a warning.
 */
class QuotaFailureModeIT extends AuthenticationIntegrationTest {
    private static final String EXTERNAL_EMAIL = "fixture@external.invalid";
    private static final AtomicInteger RUNS = new AtomicInteger();

    /** What an open action answers once it has run: its own result, not the 503 of the counters. */
    private static final Map<QuotaScope, String> WHEN_OPEN =
            Map.of(
                    QuotaScope.CONFIRMATION, "400",
                    QuotaScope.GOOGLE_EMAIL_CONFIRM, "400",
                    QuotaScope.GOOGLE_START, "200",
                    QuotaScope.GOOGLE_CALLBACK, "CANCELLED",
                    QuotaScope.GOOGLE_COMPLETE, "200",
                    QuotaScope.RESUME, "200");

    /**
     * How Redis fails the counters: it does not answer, or it refuses every write as a full memory
     * does.
     */
    private enum Outage {
        PAUSED,
        MEMORY_FULL;

        AutoCloseable begin() {
            return this == PAUSED ? Containers.pauseRedis() : Containers.fillRedisMemory();
        }
    }

    private interface Action {
        String run() throws Exception;
    }

    @Autowired AccountRepository accounts;
    @Autowired MeterRegistry meters;
    private String password;

    @BeforeEach
    void fixture() {
        password = TestPasswords.random();
    }

    @Override
    protected GoogleBrowser newBrowser() {
        return new GoogleBrowser(port, json);
    }

    private static String status(HttpResponse<String> response) {
        return Integer.toString(response.statusCode());
    }

    private Action prepare(QuotaScope scope) throws Exception {
        var browser = (GoogleBrowser) newBrowser();
        String token = "t".repeat(80);
        return switch (scope) {
            case LOGIN ->
                    () ->
                            status(
                                    browser.post(
                                            "login",
                                            Map.of("identifier", "missing", "password", password)));
            case REGISTRATION_MAIL ->
                    () ->
                            status(
                                    browser.post(
                                            "signup",
                                            Map.of(
                                                    "username",
                                                    "Jeune_matrix",
                                                    "email",
                                                    "matrix@example.invalid",
                                                    "password",
                                                    password,
                                                    "confirmation",
                                                    password,
                                                    "acceptedTerms",
                                                    true,
                                                    "rememberMe",
                                                    false)));
            case CONFIRMATION -> {
                var signup =
                        browser.post(
                                "signup",
                                Map.of(
                                        "username",
                                        "Jeune_matrix",
                                        "email",
                                        "matrix@example.invalid",
                                        "password",
                                        password,
                                        "confirmation",
                                        password,
                                        "acceptedTerms",
                                        true,
                                        "rememberMe",
                                        false));
                assertThat(signup.statusCode()).as("given: signup accepted").isEqualTo(202);
                yield () ->
                        status(
                                browser.post(
                                        "signup/confirm",
                                        Map.of(
                                                "code", "123456",
                                                "registration", browser.registration)));
            }
            case RECOVERY_MAIL ->
                    () ->
                            status(
                                    browser.post(
                                            "password/forgot",
                                            Map.of("email", "matrix@example.invalid")));
            case RECOVERY_CONFIRM ->
                    () ->
                            status(
                                    browser.post(
                                            "password/reset",
                                            Map.of(
                                                    "token",
                                                    token,
                                                    "password",
                                                    password,
                                                    "confirmation",
                                                    password)));
            case GOOGLE_START -> () -> status(browser.post("google/start", Map.of()));
            case GOOGLE_CALLBACK -> {
                var flow = browser.start();
                yield () -> {
                    browser.callback(flow, true);
                    return browser.errorCode();
                };
            }
            case GOOGLE_COMPLETE -> {
                pending(browser);
                yield () -> status(browser.post("google/signup", Map.of("username", "Jeune")));
            }
            case GOOGLE_MAIL -> {
                FakeGoogle.email = EXTERNAL_EMAIL;
                pending(browser);
                yield () -> status(browser.post("google/email", Map.of()));
            }
            case GOOGLE_LINK -> {
                // The owner of a local account with the email of Google, who typed the password:
                // the link may be made.
                accounts.save(
                        new Account(
                                "Ecole_demo",
                                FakeGoogle.email,
                                passwords.encode(password),
                                true,
                                true));
                pending(browser);
                var access =
                        browser.post(
                                "google/access",
                                Map.of("identifier", FakeGoogle.email, "password", password));
                assertThat(access.statusCode()).as("given: access accepted").isEqualTo(200);
                yield () -> status(browser.post("google/link", Map.of("confirmed", true)));
            }
            case GOOGLE_EMAIL_CONFIRM ->
                    () -> status(browser.post("google/email/confirm", Map.of("token", token)));
            case RESUME -> {
                accounts.save(
                        new Account(
                                "Ecole_demo",
                                "jeune@example.invalid",
                                passwords.encode(password),
                                true,
                                true));
                var login =
                        browser.post(
                                "login",
                                Map.of(
                                        "identifier",
                                        "jeune@example.invalid",
                                        "password",
                                        password,
                                        "rememberMe",
                                        true));
                assertThat(login.statusCode()).as("given: login accepted").isEqualTo(200);
                yield () -> status(browser.post("resume", ""));
            }
        };
    }

    private void pending(GoogleBrowser browser) throws Exception {
        var flow = browser.start();
        assertThat(browser.callback(flow, false).statusCode())
                .as("given: callback accepted")
                .isEqualTo(302);
        assertThat(browser.pending()).as("given: flow pending").isTrue();
    }

    /**
     * Refusals and executions of the actions whose counters failed; the context is shared, so tests
     * take deltas.
     */
    private double unavailable(QuotaScope scope, String outcome) {
        return meters.get("auth.rate_limit.unavailable")
                .tag("scope", scope.key())
                .tag("outcome", outcome)
                .counter()
                .count();
    }

    static Stream<Arguments> closedScopes() {
        return scopesWith(FailureMode.CLOSED);
    }

    static Stream<Arguments> openScopes() {
        return scopesWith(FailureMode.OPEN);
    }

    private static Stream<Arguments> scopesWith(FailureMode mode) {
        return Arrays.stream(QuotaScope.values())
                .filter(scope -> scope.failureMode() == mode)
                .flatMap(
                        scope ->
                                Arrays.stream(Outage.values())
                                        .map(outage -> arguments(scope, outage)));
    }

    @ParameterizedTest(name = "{0} : {1}")
    @MethodSource("closedScopes")
    void closedActionAnswers503AndSendsNoEmailWhileRedisIsUnavailable(
            QuotaScope scope, Outage outage) throws Exception {
        var action = prepare(scope);
        var mail = new MailCounters();
        var logs = LogCapture.of(QuotaEnforcer.class);
        double refusedBefore = unavailable(scope, "refused");

        String outcome;
        try (logs;
                var _ = outage.begin()) {
            outcome = action.run();
        }

        assertThat(outcome).as("outcome").isEqualTo("503");
        assertThat(unavailable(scope, "refused") - refusedBefore)
                .as("counted refusals")
                .isEqualTo(1);
        assertThat(mail.calls()).as("provider calls").isZero();
        assertThat(count("auth_google_identities")).as("linked identities").isZero();
        assertThat(logs.events()).as("warnings of executions without control").isEmpty();
    }

    @ParameterizedTest(name = "{0} : {1}")
    @MethodSource("openScopes")
    void openActionRunsAndWritesTheWarningWhileRedisIsUnavailable(QuotaScope scope, Outage outage)
            throws Exception {
        // The warning is written once a minute per action and the context is shared: each test
        // moves the clock two
        // minutes further, and prepares its flows after that move so they do not expire.
        clock.now = clock.now.plus(Duration.ofMinutes(2L * RUNS.incrementAndGet()));
        var action = prepare(scope);
        var logs = LogCapture.of(QuotaEnforcer.class);
        double executedBefore = unavailable(scope, "executed");

        String outcome;
        try (logs;
                var _ = outage.begin()) {
            outcome = action.run();
        }

        assertThat(outcome).as("outcome").isEqualTo(WHEN_OPEN.get(scope));
        assertThat(unavailable(scope, "executed") - executedBefore)
                .as("counted executions")
                .isEqualTo(1);
        assertThat(logs.events())
                .as("warnings")
                .singleElement()
                .satisfies(
                        event -> {
                            assertThat(event.getLevel()).as("level").isEqualTo(Level.WARN);
                            assertThat(event.getFormattedMessage())
                                    .as("message")
                                    .contains(scope.key());
                        });
    }
}
