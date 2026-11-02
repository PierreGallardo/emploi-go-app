package fr.emploigo.auth.infrastructure.quota;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;

import fr.emploigo.auth.application.quota.LoginAttemptLimiter.Admitted;
import fr.emploigo.auth.application.quota.LoginAttemptLimiter.Limited;
import fr.emploigo.auth.application.quota.LoginAttemptLimiter.Unavailable;
import fr.emploigo.support.LogCapture;
import fr.emploigo.support.TestClock;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * What the adapter of the login answers for each reply of Redis, without a server.
 */
@SuppressWarnings("unchecked")
class RedisLoginAttemptLimiterTest {
    private static final String IDENTITY = "jeune@example.invalid";
    private static final String ADDRESS = "203.0.113.7";

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final TestClock clock = new TestClock();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final RedisLoginAttemptLimiter limiter =
            new RedisLoginAttemptLimiter(redis, TestQuotaKeys.KEYS, clock, meters);
    private final fr.emploigo.auth.application.quota.LoginLimits limits =
            QuotaTestProperties.loginLimits();

    /** begin: the wait in ms, then 1 when the witness was just created. */
    private void begins(List<?> reply) {
        when(redis.execute(
                        any(RedisScript.class),
                        anyList(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any()))
                .thenReturn(reply);
    }

    private void begins(long wait, long lost) {
        begins(List.of(wait, lost));
    }

    private void beginFails(RuntimeException error) {
        when(redis.execute(
                        any(RedisScript.class),
                        anyList(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any()))
                .thenThrow(error);
    }

    private Object begin() {
        return limiter.begin(IDENTITY, ADDRESS, limits);
    }

    private double lost() {
        return meters.get("auth.rate_limit.counters.lost").counter().count();
    }

    @Test
    void zeroWaitAdmitsTheAttempt() {
        begins(0, 0);

        assertThat(begin()).as("admission").isEqualTo(new Admitted());
    }

    @Test
    void positiveWaitIsTheDelayInMilliseconds() {
        begins(4_200, 0);

        assertThat(begin()).as("admission").isEqualTo(new Limited(Duration.ofMillis(4_200)));
    }

    static Stream<Arguments> unreadableReplies() {
        return Stream.of(
                argumentSet("incomplète", List.of(0L)),
                argumentSet("trop longue", List.of(0L, 0L, 1L)),
                argumentSet("sans attente", Arrays.asList(null, 0L)),
                argumentSet("sans témoin", Arrays.asList(0L, null)),
                argumentSet("en texte", List.of("0", "0")));
    }

    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("unreadableReplies")
    void unreadableReplyAnswersUnavailableAndIsNoLoss(List<?> reply) {
        begins(reply);

        assertThat(begin()).as("admission").isEqualTo(new Unavailable());
        assertThat(lost()).as("losses").isZero();
    }

    @Test
    void failureOfRedisMeansUnavailableAtEveryBegin() {
        beginFails(new RedisConnectionFailureException("connexion coupée"));

        var admissions = IntStream.range(0, 5).mapToObj(_ -> begin()).toList();

        assertThat(admissions).as("admissions").containsOnly(new Unavailable());
    }

    @Test
    void lossIsCountedAndLoggedWithoutIdentityOrAddressAndTheAttemptIsAdmitted() {
        begins(0, 1);

        try (var logs = LogCapture.of(RedisLoginAttemptLimiter.class)) {
            var admission = begin();

            assertThat(admission).as("admission").isEqualTo(new Admitted());
            assertThat(lost()).as("losses").isEqualTo(1);
            assertThat(logs.events())
                    .as("events")
                    .singleElement()
                    .satisfies(
                            event -> {
                                assertThat(event.getLevel()).as("level").isEqualTo(Level.WARN);
                                assertThat(event.getFormattedMessage())
                                        .as("message")
                                        .doesNotContain(IDENTITY)
                                        .doesNotContain(ADDRESS);
                            });
        }
    }

    @Test
    void failuresOfTheCountersAreLoggedOncePerMinuteWithTheCauseAndNoIdentity() {
        beginFails(new RedisConnectionFailureException("connexion coupée"));

        try (var logs = LogCapture.of(RedisLoginAttemptLimiter.class)) {
            IntStream.range(0, 3).forEach(_ -> begin());

            assertThat(logs.events())
                    .as("events")
                    .singleElement()
                    .satisfies(
                            event -> {
                                assertThat(event.getLevel()).as("level").isEqualTo(Level.WARN);
                                assertThat(event.getFormattedMessage())
                                        .as("message")
                                        .contains("RedisConnectionFailureException")
                                        .doesNotContain(IDENTITY)
                                        .doesNotContain(ADDRESS);
                            });
        }
    }

    @Test
    void failedForgetIsNotRecordedNeverThrows() {
        when(redis.delete(anyCollection()))
                .thenThrow(new RedisConnectionFailureException("connexion coupée"));

        assertThat(limiter.forget(IDENTITY)).as("recorded").isFalse();
    }

    @Test
    void failedForgetIsLoggedWithoutIdentity() {
        when(redis.delete(anyCollection()))
                .thenThrow(new RedisConnectionFailureException("connexion coupée"));

        try (var logs = LogCapture.of(RedisLoginAttemptLimiter.class)) {
            limiter.forget(IDENTITY);

            assertThat(logs.events())
                    .as("events")
                    .singleElement()
                    .satisfies(
                            event ->
                                    assertThat(event.getFormattedMessage())
                                            .as("message")
                                            .contains("forget")
                                            .doesNotContain(IDENTITY));
        }
    }

    @Test
    void unreadableReplyOfTheForgetIsNotRecorded() {
        when(redis.delete(anyCollection())).thenReturn(null);

        assertThat(limiter.forget(IDENTITY)).as("recorded").isFalse();
    }
}
