package fr.emploigo.auth.infrastructure.quota;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;

import fr.emploigo.auth.application.quota.AuthenticationRateLimiter.Allowed;
import fr.emploigo.auth.application.quota.AuthenticationRateLimiter.Cause;
import fr.emploigo.auth.application.quota.AuthenticationRateLimiter.Limited;
import fr.emploigo.auth.application.quota.AuthenticationRateLimiter.Unavailable;
import fr.emploigo.auth.application.quota.QuotaScope;
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
 * What the adapter answers for each reply of Redis, without a server.
 */
@SuppressWarnings("unchecked")
class RedisAuthenticationRateLimiterTest {
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final TestClock clock = new TestClock();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final RedisAuthenticationRateLimiter limiter =
            new RedisAuthenticationRateLimiter(redis, TestQuotaKeys.KEYS, clock, meters);

    /**
     * Script reply: the wait in milliseconds, then 1 when the counter witness was just created,
     * then 1 when the global alert threshold is exceeded, then 1 when the prefix refused, then 1
     * when the delay between two sends refused.
     */
    private void replies(long wait, long lost) {
        replies(List.of(wait, lost, 0L, 0L, 0L));
    }

    private void replies(long wait, long lost, long exceeded) {
        replies(List.of(wait, lost, exceeded, 0L, 0L));
    }

    private void replies(List<?> reply) {
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

    private double lost() {
        return meters.get("auth.rate_limit.counters.lost").counter().count();
    }

    private void fails(RuntimeException error) {
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

    private Object check() {
        return limiter.check(
                QuotaScope.RECOVERY_CONFIRM,
                "account:42",
                "203.0.113.7",
                QuotaTestProperties.limits(QuotaScope.RECOVERY_CONFIRM));
    }

    private Object checkMail() {
        return limiter.check(
                QuotaScope.REGISTRATION_MAIL,
                "email:jeune@example.invalid",
                "203.0.113.7",
                QuotaTestProperties.limits(QuotaScope.REGISTRATION_MAIL));
    }

    private double prefixLimited(QuotaScope scope) {
        return meters.get("auth.rate_limit.prefix.limited")
                .tag("scope", scope.key())
                .counter()
                .count();
    }

    private double exceeded(QuotaScope scope) {
        var counter =
                meters.find("auth.rate_limit.global.exceeded").tag("scope", scope.key()).counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void loginHasItsOwnLimiterSoTheGenericOneRefusesIt() {
        assertThatThrownBy(
                        () ->
                                limiter.check(
                                        QuotaScope.LOGIN,
                                        "account:42",
                                        "203.0.113.7",
                                        QuotaTestProperties.limits(QuotaScope.RECOVERY_CONFIRM)))
                .as("check")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("login");
    }

    private void identityReplies(long wait, long lost) {
        when(redis.execute(any(RedisScript.class), anyList(), any(), any()))
                .thenReturn(List.of(wait, lost, 0L, 0L, 0L));
    }

    private Object checkIdentity() {
        return limiter.checkIdentity(
                QuotaScope.RESUME,
                "family-1",
                QuotaTestProperties.identityLimits(QuotaScope.RESUME));
    }

    @Test
    void reconnectionHasNoAddressSoTheGenericCheckRefusesIt() {
        assertThatThrownBy(
                        () ->
                                limiter.check(
                                        QuotaScope.RESUME,
                                        "family-1",
                                        "203.0.113.7",
                                        QuotaTestProperties.limits(QuotaScope.RECOVERY_CONFIRM)))
                .as("check")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("resume");
    }

    @Test
    void anActionWithAnAddressIsRefusedByTheCheckOfAnIdentityAlone() {
        assertThatThrownBy(
                        () ->
                                limiter.checkIdentity(
                                        QuotaScope.RECOVERY_CONFIRM,
                                        "family-1",
                                        QuotaTestProperties.identityLimits(QuotaScope.RESUME)))
                .as("check")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("recovery-confirm");
    }

    @Test
    void identityAlonePositiveReplyIsTheWaitInMilliseconds() {
        identityReplies(61_200, 0);

        assertThat(checkIdentity())
                .as("outcome")
                .isEqualTo(new Limited(Duration.ofMillis(61_200), Cause.VOLUME));
    }

    @Test
    void zeroMeansAllowed() {
        replies(0, 0);

        assertThat(check()).as("outcome").isEqualTo(new Allowed());
    }

    @Test
    void positiveReplyIsTheWaitInMilliseconds() {
        replies(61_200, 0);

        assertThat(check())
                .as("outcome")
                .isEqualTo(new Limited(Duration.ofMillis(61_200), Cause.VOLUME));
    }

    @Test
    void sendDelayRefusalIsLimitedWithTheWaitOfTheDelayAndItsCause() {
        replies(List.of(42_000L, 0L, 0L, 0L, 1L));

        var outcome = checkMail();

        assertThat(outcome)
                .as("outcome")
                .isEqualTo(new Limited(Duration.ofSeconds(42), Cause.SEND_DELAY));
    }

    @Test
    void failureOfRedisAnswersUnavailableAtEveryCheck() {
        fails(new RedisConnectionFailureException("connexion coupée"));

        var outcomes = IntStream.range(0, 5).mapToObj(_ -> check()).toList();

        assertThat(outcomes).as("outcomes").containsOnly(new Unavailable());
    }

    static Stream<Arguments> unreadableReplies() {
        return Stream.of(
                argumentSet("absente", (Object) null),
                argumentSet("incomplète", List.of(0L)),
                argumentSet("sans l'indicateur du seuil", List.of(0L, 0L)),
                argumentSet("sans le refus du préfixe", List.of(0L, 0L, 0L)),
                argumentSet("sans le délai entre deux envois", List.of(0L, 0L, 0L, 0L)),
                argumentSet("trop longue", List.of(0L, 1L, 0L, 0L, 0L, 2L)),
                argumentSet("sans témoin", Arrays.asList(0L, null, 0L, 0L, 0L)),
                argumentSet("sans attente", Arrays.asList(null, 0L, 0L, 0L, 0L)),
                argumentSet("sans seuil", Arrays.asList(0L, 0L, null, 0L, 0L)),
                argumentSet("sans refus du préfixe", Arrays.asList(0L, 0L, 0L, null, 0L)),
                argumentSet(
                        "sans refus du délai entre deux envois",
                        Arrays.asList(0L, 0L, 0L, 0L, null)),
                argumentSet("en texte", List.of("0", "1", "0", "0", "0")));
    }

    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("unreadableReplies")
    void unreadableReplyAnswersUnavailableAndIsNoLoss(List<?> reply) {
        replies(reply);

        assertThat(check()).as("outcome").isEqualTo(new Unavailable());
        assertThat(lost()).as("losses").isZero();
    }

    @Test
    void counterOfLossesIsVisibleAtZeroBeforeAnyLoss() {
        assertThat(lost()).as("losses").isZero();
    }

    @Test
    void reportedLossStillAllowsTheActionAndIsCountedAndLogged() {
        replies(0, 1);

        try (var logs = LogCapture.of(RedisAuthenticationRateLimiter.class)) {
            var outcome = check();

            assertThat(outcome).as("outcome").isEqualTo(new Allowed());
            assertThat(lost()).as("losses").isEqualTo(1);
            assertThat(logs.events())
                    .as("events")
                    .singleElement()
                    .satisfies(
                            event -> {
                                assertThat(event.getLevel()).as("level").isEqualTo(Level.WARN);
                                assertThat(event.getFormattedMessage())
                                        .as("message")
                                        .contains("Redis")
                                        .contains("zéro")
                                        .doesNotContain("account:42")
                                        .doesNotContain("203.0.113.7");
                            });
        }
    }

    @Test
    void reportedLossIsCountedWhenTheCounterRefusesToo() {
        replies(30_000, 1);

        var outcome = check();

        assertThat(outcome)
                .as("outcome")
                .isEqualTo(new Limited(Duration.ofSeconds(30), Cause.VOLUME));
        assertThat(lost()).as("losses").isEqualTo(1);
    }

    @Test
    void noLossIsReportedWhenTheWitnessWasThere() {
        replies(0, 0);

        try (var logs = LogCapture.of(RedisAuthenticationRateLimiter.class)) {
            check();

            assertThat(logs.events()).as("events").isEmpty();
        }
        assertThat(lost()).as("losses").isZero();
    }

    @Test
    void lossesAreLoggedOncePerMinuteWhileEveryOneIsCounted() {
        replies(0, 1);

        try (var logs = LogCapture.of(RedisAuthenticationRateLimiter.class)) {
            check();
            clock.now = clock.now.plusSeconds(59);
            check();
            assertThat(logs.events()).as("warnings within the minute").hasSize(1);

            clock.now = clock.now.plusSeconds(1);
            check();
            assertThat(logs.events()).as("warnings after a minute").hasSize(2);
        }
        assertThat(lost()).as("losses").isEqualTo(3);
    }

    @Test
    void errorOfTheJvmIsRethrownAndAnswersNothing() {
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
                .thenThrow(new StackOverflowError());

        assertThatThrownBy(this::check)
                .as("error of the JVM")
                .isInstanceOf(StackOverflowError.class);
    }

    @Test
    void failuresAreLoggedOncePerMinuteWithTheCauseAndNoIdentity() {
        fails(new RedisConnectionFailureException("connexion coupée"));

        try (var logs = LogCapture.of(RedisAuthenticationRateLimiter.class)) {
            IntStream.range(0, 3).forEach(_ -> check());

            assertThat(logs.events())
                    .as("events")
                    .singleElement()
                    .satisfies(
                            event -> {
                                assertThat(event.getLevel()).as("level").isEqualTo(Level.WARN);
                                assertThat(event.getFormattedMessage())
                                        .as("message")
                                        .contains("RedisConnectionFailureException")
                                        .doesNotContain("account:42")
                                        .doesNotContain("203.0.113.7");
                            });
        }
    }

    @Test
    void prefixRefusalIsLimitedWithTheWaitOfThePrefixAndCountedAndWarned() {
        replies(List.of(540_000L, 0L, 0L, 1L, 0L));

        try (var logs = LogCapture.of(RedisAuthenticationRateLimiter.class)) {
            var outcome =
                    limiter.check(
                            QuotaScope.REGISTRATION_MAIL,
                            "email:jeune@example.invalid",
                            "v6:20010db80001000",
                            QuotaTestProperties.limits(QuotaScope.REGISTRATION_MAIL));

            assertThat(outcome)
                    .as("outcome")
                    .isEqualTo(new Limited(Duration.ofMillis(540_000), Cause.VOLUME));
            assertThat(logs.events())
                    .as("events")
                    .singleElement()
                    .satisfies(
                            event -> {
                                assertThat(event.getLevel()).as("level").isEqualTo(Level.WARN);
                                assertThat(event.getFormattedMessage())
                                        .as("message")
                                        .contains("registration-mail")
                                        .doesNotContain("20010db8")
                                        .doesNotContain("jeune@example.invalid");
                            });
        }
        assertThat(prefixLimited(QuotaScope.REGISTRATION_MAIL))
                .as("refusals by prefix")
                .isEqualTo(1);
    }

    @Test
    void prefixRefusalsAreWarnedAtMostOncePerMinuteAndPerAction() {
        replies(List.of(1_000L, 0L, 0L, 1L, 0L));

        try (var logs = LogCapture.of(RedisAuthenticationRateLimiter.class)) {
            checkMail();
            checkMail();
            clock.now = clock.now.plusSeconds(61);
            checkMail();

            assertThat(logs.events()).as("warnings").hasSize(2);
        }
        assertThat(prefixLimited(QuotaScope.REGISTRATION_MAIL))
                .as("refusals by prefix")
                .isEqualTo(3);
    }

    @Test
    void everyEmailActionCountsItsPrefixRefusalsFromZero() {
        assertThat(
                        mailScopes()
                                .map(
                                        scope ->
                                                meters.get("auth.rate_limit.prefix.limited")
                                                        .tag("scope", scope.key())
                                                        .counter()
                                                        .count())
                                .toList())
                .as("counts before any refusal")
                .containsOnly(0.0);
    }

    @Test
    void thresholdExceededStillAllowsTheActionAndIsCounted() {
        replies(0, 0, 1);

        var outcome = checkMail();

        assertThat(outcome).as("outcome").isEqualTo(new Allowed());
        assertThat(exceeded(QuotaScope.REGISTRATION_MAIL)).as("exceeded").isEqualTo(1);
    }

    @Test
    void thresholdExceededWritesAWarningWithoutIdentityOrAddress() {
        replies(0, 0, 1);

        try (var logs = LogCapture.of(RedisAuthenticationRateLimiter.class)) {
            checkMail();

            assertThat(logs.events())
                    .as("events")
                    .singleElement()
                    .satisfies(
                            event -> {
                                assertThat(event.getLevel()).as("level").isEqualTo(Level.WARN);
                                assertThat(event.getFormattedMessage())
                                        .as("message")
                                        .contains("registration-mail")
                                        .doesNotContain("jeune@example.invalid")
                                        .doesNotContain("203.0.113.7");
                            });
        }
    }

    @Test
    void thresholdWarningIsWrittenOncePerScopeAndPerMinuteWhileEveryOverrunIsCounted() {
        replies(0, 0, 1);

        try (var logs = LogCapture.of(RedisAuthenticationRateLimiter.class)) {
            checkMail();
            clock.now = clock.now.plusSeconds(59);
            checkMail();
            assertThat(logs.events()).as("warnings within the minute").hasSize(1);

            clock.now = clock.now.plusSeconds(1);
            checkMail();
            assertThat(logs.events()).as("warnings after a minute").hasSize(2);
        }
        assertThat(exceeded(QuotaScope.REGISTRATION_MAIL)).as("exceeded").isEqualTo(3);
    }

    @Test
    void thresholdNotExceededWritesNothingAndCountsNothing() {
        replies(0, 0, 0);

        try (var logs = LogCapture.of(RedisAuthenticationRateLimiter.class)) {
            checkMail();

            assertThat(logs.events()).as("events").isEmpty();
        }
        assertThat(exceeded(QuotaScope.REGISTRATION_MAIL)).as("exceeded").isZero();
    }

    static Stream<QuotaScope> mailScopes() {
        return Arrays.stream(QuotaScope.values()).filter(QuotaScope::mail);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("mailScopes")
    void thresholdCounterOfAnEmailActionIsVisibleAtZeroBeforeAnyOverrun(QuotaScope scope) {
        assertThat(
                        meters.find("auth.rate_limit.global.exceeded")
                                .tag("scope", scope.key())
                                .counter())
                .as("counter")
                .isNotNull()
                .extracting(counter -> counter.count())
                .isEqualTo(0.0);
    }

    @Test
    void actionWithoutEmailHasNoThresholdCounter() {
        assertThat(meters.find("auth.rate_limit.global.exceeded").tag("scope", "login").counter())
                .as("counter")
                .isNull();
    }
}
