package fr.emploigo.auth.api.quota;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;

import fr.emploigo.auth.application.AuthenticationIntegrationTest;
import fr.emploigo.auth.application.account.AccountRepository;
import fr.emploigo.auth.application.quota.AuthenticationRateLimiter.Allowed;
import fr.emploigo.auth.application.quota.QuotaLimits;
import fr.emploigo.auth.application.quota.QuotaScope;
import fr.emploigo.auth.domain.Account;
import fr.emploigo.auth.infrastructure.quota.RedisAuthenticationRateLimiter;
import fr.emploigo.auth.infrastructure.quota.RedisLoginAttemptLimiter;
import fr.emploigo.auth.infrastructure.quota.TestQuotaKeys;
import fr.emploigo.support.Browser;
import fr.emploigo.support.Containers;
import fr.emploigo.support.LogCapture;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

/**
 * The quota counters survive a Redis restart through its persistence; a real loss empties them, is
 * reported, and blocks nothing.
 */
class AuthenticationCountersPersistenceIT extends AuthenticationIntegrationTest {
    private static final String EMAIL = "jeune@example.invalid";

    /**
     * Failures already counted: the next one is followed by the longest delay, 5 minutes, which
     * outlasts a restart.
     */
    private static final int FAILURES_BEFORE_THE_CEILING = 10;

    private static final String LOSS = "perdu ses données";

    @Autowired AccountRepository accounts;
    @Autowired MeterRegistry meters;
    private String password;

    @BeforeEach
    void fixture() {
        password = UUID.randomUUID() + " 🔑 ";
        accounts.save(
                new Account("Ecole_demo", EMAIL, passwords.encode(password), true, true));
    }

    private HttpResponse<String> login(Browser browser, String csrf, String secret)
            throws Exception {
        return browser.post("login", Map.of("identifier", EMAIL, "password", secret), csrf);
    }

    private int firstAnswerOnceRedisIsBack(Browser browser, String secret) {
        var status = new AtomicInteger();
        await().atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(100))
                .ignoreExceptions()
                .until(
                        () -> {
                            status.set(login(browser, browser.csrf(), secret).statusCode());
                            return status.get() != 503;
                        });
        return status.get();
    }

    private Browser atTheCeiling() throws Exception {
        var browser = newBrowser();
        redis.opsForValue()
                .set(
                        "auth:limits:login:failures:" + TestQuotaKeys.KEYS.identity(EMAIL),
                        Integer.toString(FAILURES_BEFORE_THE_CEILING),
                        Duration.ofMinutes(30));
        var statuses =
                browser.postStatuses("login", Map.of("identifier", EMAIL, "password", "wrong"), 2);
        assertThat(statuses).as("given: a failure, then the delay").containsExactly(401, 429);
        return browser;
    }

    private double lost() {
        return meters.get("auth.rate_limit.counters.lost").counter().count();
    }

    private static long lossLines(LogCapture logs) {
        return logs.events().stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.contains(LOSS))
                .count();
    }

    /**
     * Moves past the minute that spaces two loss lines; each test resets the clock, so an earlier
     * line never counts as recent.
     */
    private void pastTheSpacingOfTheWarnings() {
        clock.now = clock.now.plus(Duration.ofHours(1));
    }

    @Test
    void firstCallAfterTheResetOfTheTestsReportsNoLoss() throws Exception {
        double before = lost();

        try (var logs = LogCapture.of(RedisLoginAttemptLimiter.class)) {
            var browser = newBrowser();
            assertThat(login(browser, browser.csrf(), password).statusCode())
                    .as("status")
                    .isEqualTo(200);

            assertThat(lossLines(logs)).as("lines of loss").isZero();
        }
        assertThat(lost() - before).as("losses").isZero();
    }

    @Test
    void counterAtTheCeilingStaysAtTheCeilingWhenRedisRestartsAndNoLossIsReported()
            throws Exception {
        var browser = atTheCeiling();
        String before = Containers.redisRunId();
        double lossesBefore = lost();
        pastTheSpacingOfTheWarnings();

        try (var logs = LogCapture.of(RedisLoginAttemptLimiter.class)) {
            Containers.restartRedis();

            assertThat(Containers.redisRunId()).as("run id after the restart").isNotEqualTo(before);
            assertThat(firstAnswerOnceRedisIsBack(browser, password))
                    .as("status of the right password")
                    .isEqualTo(429);
            assertThat(lossLines(logs)).as("lines of loss").isZero();
        }
        assertThat(lost() - lossesBefore).as("losses").isZero();
    }

    @Test
    void redisBackWithNothingLetsTheActionsPassAndReportsTheLoss() throws Exception {
        var browser = atTheCeiling();
        double lossesBefore = lost();
        pastTheSpacingOfTheWarnings();

        try (var logs = LogCapture.of(RedisLoginAttemptLimiter.class)) {
            Containers.restartRedisEmpty();

            assertThat(firstAnswerOnceRedisIsBack(browser, password))
                    .as("status of the right password")
                    .isEqualTo(200);
            assertThat(lossLines(logs)).as("lines of loss").isEqualTo(1);
            assertThat(logs.events())
                    .as("lines")
                    .allSatisfy(
                            event -> assertThat(event.getFormattedMessage()).doesNotContain(EMAIL));
        }
        assertThat(lost() - lossesBefore).as("losses").isEqualTo(1);
    }

    @Test
    void severalInstancesOfTheApiAfterTheSameLossReportItOnce() throws Exception {
        int calls = 20;
        var registries = List.of(new SimpleMeterRegistry(), new SimpleMeterRegistry());
        var instances =
                registries.stream()
                        .map(
                                registry ->
                                        new RedisAuthenticationRateLimiter(
                                                redis, TestQuotaKeys.KEYS, clock, registry))
                        .toList();
        redis.delete(RedisAuthenticationRateLimiter.WITNESS_KEY);

        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var gate = new CountDownLatch(1);
            var outcomes =
                    IntStream.range(0, calls)
                            .mapToObj(
                                    i ->
                                            pool.submit(
                                                    () -> {
                                                        gate.await();
                                                        return instances
                                                                .get(i % instances.size())
                                                                .check(
                                                                        QuotaScope.RECOVERY_CONFIRM,
                                                                        "identity-" + i,
                                                                        "address-" + i,
                                                                        new QuotaLimits(
                                                                                30,
                                                                                10,
                                                                                Duration.ofMinutes(
                                                                                        15),
                                                                                null));
                                                    }))
                            .toList();
            gate.countDown();

            assertThat(outcomes.stream().map(unchecked(Future::get)).toList())
                    .as("outcomes")
                    .hasSize(calls)
                    .containsOnly(new Allowed());
        }

        double reported =
                registries.stream()
                        .mapToDouble(
                                registry ->
                                        registry.get("auth.rate_limit.counters.lost")
                                                .counter()
                                                .count())
                        .sum();
        assertThat(reported).as("losses reported by every instance together").isEqualTo(1);
    }
}
