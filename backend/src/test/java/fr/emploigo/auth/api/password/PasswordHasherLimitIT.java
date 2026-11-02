package fr.emploigo.auth.api.password;

import static fr.emploigo.auth.application.password.HashingGate.HELD;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.verifyNoInteractions;

import fr.emploigo.auth.application.AuthenticationIntegrationTest;
import fr.emploigo.auth.application.session.PasswordChecker;
import fr.emploigo.auth.domain.AccountIdentifier;
import fr.emploigo.auth.infrastructure.quota.TestQuotaKeys;

import io.micrometer.core.instrument.MeterRegistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

/**
 * Simultaneous password computations seen from HTTP. The encoder is held by a gate, so no test
 * depends on PBKDF2 timing or core count.
 */
class PasswordHasherLimitIT extends AuthenticationIntegrationTest {
    private static final String IDENTIFIER = "Saturation";
    private static final String PASSWORD = "Une phrase fictive 1 🔑 pour le plafond";
    private static final String ANOTHER_PASSWORD = "Une autre phrase fictive 2 🔑";

    /**
     * Failures of one identity that no delay follows: the default of {@code
     * login.failure-delay.free-failures}.
     */
    private static final int FREE_FAILURES = 5;

    private static final String MESSAGE =
            "Le service est très sollicité. Réessaie dans quelques secondes.";

    @Autowired MeterRegistry meters;
    @Autowired PasswordChecker checker;
    private double rejectionsBefore;

    @BeforeEach
    void fixture() {
        // Spring Security computes its dummy hash on the first login: done before the gate, so a
        // refusal below is always that of a password check.
        checker.check("echauffement", PASSWORD);
        rejectionsBefore = rejections();
    }

    /** Refusals of the limit counted since the start of the test, for both computations. */
    private double rejections() {
        return meters.find("auth.password.hashing.rejections").counters().stream()
                .mapToDouble(counter -> counter.count())
                .sum();
    }

    private void account(String username, String secret) {
        jdbc.update(
                """
                INSERT INTO auth_accounts(
                    username, email_key, password_hash, email_verified, enabled)
                VALUES (?, ?, ?, TRUE, TRUE)
                """,
                AccountIdentifier.username(username),
                username.toLowerCase() + "@example.invalid",
                passwords.encode(secret));
    }

    private HttpResponse<String> login(String identifier, String secret) throws Exception {
        return newBrowser().post("login", Map.of("identifier", identifier, "password", secret));
    }

    private void assertRefusedByTheLimit(HttpResponse<String> response) {
        assertThat(response.statusCode()).as("status").isEqualTo(503);
        assertThat(response.headers().firstValue("Retry-After")).as("Retry-After").contains("5");
        assertThat(response.headers().firstValue("Cache-Control"))
                .as("Cache-Control")
                .contains("no-store");
        assertThat(code(response)).as("code").isEqualTo("BUSY");
        assertThat(json.readTree(response.body()).path("message").asString())
                .as("message")
                .isEqualTo(MESSAGE);
    }

    @Test
    void moreLoginsThanTheLimitComputeOnlyTheLimitAndRefuseTheOthersAtOnce() throws Exception {
        int limit = hashing.limit();

        try (var gate = hashingGate()) {
            var logins =
                    IntStream.range(0, limit + 3)
                            .mapToObj(
                                    number ->
                                            gate.submit(
                                                    () -> login("absent " + number, HELD + number)))
                            .toList();
            gate.awaitRunning(limit);
            await().alias("three refusals while the computations are held")
                    .atMost(Duration.ofSeconds(10))
                    .until(() -> logins.stream().filter(Future::isDone).count() == 3);
            var refused =
                    logins.stream()
                            .filter(Future::isDone)
                            .map(unchecked(future -> future.get()))
                            .toList();

            refused.forEach(this::assertRefusedByTheLimit);
            assertThat(gate.running())
                    .as("computations in progress during the refusals")
                    .isEqualTo(limit);

            gate.release();
            var statuses =
                    logins.stream()
                            .map(unchecked(future -> future.get()))
                            .map(HttpResponse::statusCode)
                            .toList();
            assertThat(statuses).as("refused").filteredOn(status -> status == 503).hasSize(3);
            assertThat(statuses)
                    .as("computed, then refused as unknown accounts")
                    .filteredOn(status -> status == 401)
                    .hasSize(limit);
            assertThat(gate.peak())
                    .as("largest number of simultaneous computations")
                    .isEqualTo(limit);
            assertThat(rejections() - rejectionsBefore).as("refusals counted").isEqualTo(3);
        }
    }

    private static String failuresKey(String typed) {
        return "auth:limits:login:failures:"
                + TestQuotaKeys.KEYS.identity(AccountIdentifier.loginKey(typed));
    }

    private static String delayKey(String typed) {
        return "auth:limits:login:delay:"
                + TestQuotaKeys.KEYS.identity(AccountIdentifier.loginKey(typed));
    }

    @Test
    void loginOfAnUnknownAccountAtTheLimitIsRefusedWithoutCheckingThePassword() throws Exception {
        try (var gate = hashingGate()) {
            gate.saturate(hashing.limit());

            var response = login("absent", PASSWORD);

            assertRefusedByTheLimit(response);
            // The password was never checked, but the interrupted attempt keeps its failure: the
            // identity has one counted failure, and no delay yet.
            assertThat(redis.opsForValue().get(failuresKey("absent")))
                    .as("failures of the identity")
                    .isEqualTo("1");
            assertThat(redis.hasKey(delayKey("absent"))).as("delay of the identity").isFalse();
        }
    }

    @Test
    void signupAtTheLimitIsRefusedBeforeTheQuotaAndSucceedsOnceFree() throws Exception {
        var body =
                Map.of(
                        "username",
                        "Nouveau",
                        "email",
                        "nouveau@example.invalid",
                        "password",
                        PASSWORD,
                        "confirmation",
                        PASSWORD,
                        "acceptedTerms",
                        true,
                        "rememberMe",
                        false);

        try (var gate = hashingGate()) {
            gate.saturate(hashing.limit());

            var refused = newBrowser().post("signup", body);

            assertRefusedByTheLimit(refused);
            verifyNoInteractions(limiter);
            assertThat(count("auth_registrations")).as("registrations").isZero();

            gate.releaseAndWait();
            // The capacity is checked before the quota, so the refused request did not use the
            // address cooldown.
            var accepted = newBrowser().post("signup", body);

            assertThat(accepted.statusCode()).as("status once free").isEqualTo(202);
            assertThat(count("auth_registrations")).as("registrations once free").isEqualTo(1);
        }
    }
}
