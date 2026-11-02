package fr.emploigo.auth.infrastructure.quota;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import fr.emploigo.auth.application.AuthenticationIntegrationTest;
import fr.emploigo.auth.application.quota.LoginAttemptLimiter.Admission;
import fr.emploigo.auth.application.quota.LoginAttemptLimiter.Admitted;
import fr.emploigo.auth.application.quota.LoginAttemptLimiter.Limited;
import fr.emploigo.auth.application.quota.LoginAttemptLimiter;
import fr.emploigo.auth.application.quota.LoginLimits.FailureDelay;
import fr.emploigo.auth.application.quota.LoginLimits;

import io.micrometer.core.instrument.MeterRegistry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import java.util.stream.Stream;

class RedisLoginAttemptLimiterIT extends AuthenticationIntegrationTest {
    private static final String IDENTITY = "jeune@example.invalid";
    private static final String ADDRESS = "203.0.113.7";
    private static final Duration WINDOW = Duration.ofMinutes(15);
    private static final FailureDelay DEFAULTS =
            new FailureDelay(
                    5, Duration.ofSeconds(5), 2.0, Duration.ofMinutes(5), Duration.ofMinutes(30));
    private static final LoginLimits LIMITS = new LoginLimits(30, WINDOW, DEFAULTS);

    @Autowired LoginAttemptLimiter limiter;
    @Autowired MeterRegistry meters;

    private static String failuresKey(String identity) {
        return "auth:limits:login:failures:" + TestQuotaKeys.KEYS.identity(identity);
    }

    private static String delayKey(String identity) {
        return "auth:limits:login:delay:" + TestQuotaKeys.KEYS.identity(identity);
    }

    private static String addressKey(String address) {
        return "auth:limits:ip:" + TestQuotaKeys.KEYS.address(address);
    }

    private Admission begin(String identity, String address, LoginLimits limits) {
        return limiter.begin(identity, address, limits);
    }

    private void failures(String identity, long count) {
        redis.opsForValue()
                .set(failuresKey(identity), Long.toString(count), Duration.ofMinutes(30));
    }

    /** Remaining life of the delay of an identity, in milliseconds; negative when there is none. */
    private long delayLeft(String identity) {
        return redis.getExpire(delayKey(identity), TimeUnit.MILLISECONDS);
    }

    static Stream<Arguments> curve() {
        return Stream.of(
                argumentSet("5e échec : 5 s", 5, 5_000L),
                argumentSet("6e échec : 10 s", 6, 10_000L),
                argumentSet("7e échec : 20 s", 7, 20_000L),
                argumentSet("8e échec : 40 s", 8, 40_000L),
                argumentSet("9e échec : 80 s", 9, 80_000L),
                argumentSet("10e échec : 160 s", 10, 160_000L),
                argumentSet("11e échec : le plafond de 5 min", 11, 300_000L),
                argumentSet("12e échec : toujours le plafond", 12, 300_000L),
                argumentSet("200e échec : toujours le plafond", 200, 300_000L));
    }

    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("curve")
    void delayAfterTheNthFailureFollowsTheCurveOfTheDefaults(long failure, long expectedMillis) {
        failures(IDENTITY, failure - 1);

        begin(IDENTITY, ADDRESS, LIMITS);

        assertThat(redis.opsForValue().get(failuresKey(IDENTITY)))
                .as("failures counted")
                .isEqualTo(Long.toString(failure));
        assertThat(delayLeft(IDENTITY))
                .as("delay left, in ms")
                .isBetween(expectedMillis - 2_000, expectedMillis);
    }

    @ParameterizedTest(name = "{0} échecs déjà comptés")
    @ValueSource(longs = {0, 1, 3})
    void failuresBeforeTheFreeOnesAreNotFollowedByAnyDelay(long before) {
        failures(IDENTITY, before);

        var admission = begin(IDENTITY, ADDRESS, LIMITS);

        assertThat(admission).as("admission").isInstanceOf(Admitted.class);
        assertThat(redis.hasKey(delayKey(IDENTITY))).as("delay key").isFalse();
    }

    static Stream<Arguments> otherCurve() {
        return Stream.of(
                argumentSet("3e échec : base de 2 s", 3, 2_000L),
                        argumentSet("4e échec : ×3", 4, 6_000L),
                argumentSet("5e échec : ×9", 5, 18_000L),
                        argumentSet("6e échec : plafond de 20 s", 6, 20_000L));
    }

    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("otherCurve")
    void curveComesFromTheLimitsOfTheCallAndNotFromConstants(long failure, long expectedMillis) {
        var limits =
                new LoginLimits(
                        30,
                        WINDOW,
                        new FailureDelay(
                                3,
                                Duration.ofSeconds(2),
                                3.0,
                                Duration.ofSeconds(20),
                                Duration.ofMinutes(10)));
        failures(IDENTITY, failure - 1);

        begin(IDENTITY, ADDRESS, limits);

        assertThat(delayLeft(IDENTITY))
                .as("delay left, in ms")
                .isBetween(expectedMillis - 2_000, expectedMillis);
    }

    @Test
    void attemptDuringTheDelayIsLimitedWithTheRemainingDelay() {
        failures(IDENTITY, 4);
        begin(IDENTITY, ADDRESS, LIMITS);

        var admission = begin(IDENTITY, ADDRESS, LIMITS);

        assertThat(admission)
                .as("admission")
                .isInstanceOfSatisfying(
                        Limited.class,
                        limited ->
                                assertThat(limited.retryAfter())
                                        .as("retry after")
                                        .isPositive()
                                        .isLessThanOrEqualTo(Duration.ofSeconds(5)));
    }

    @Test
    void attemptRefusedByTheDelayCountsNowhereAndDoesNotExtendTheDelay() {
        failures(IDENTITY, 5);
        // A delay of a length the script would not set for itself: a refusal that rewrote it would
        // show.
        redis.opsForValue().set(delayKey(IDENTITY), "5", Duration.ofSeconds(60));
        var address = redis.opsForValue().get(addressKey(ADDRESS));

        var admissions =
                IntStream.range(0, 20).mapToObj(_ -> begin(IDENTITY, ADDRESS, LIMITS)).toList();

        assertThat(admissions)
                .as("admissions")
                .allSatisfy(admission -> assertThat(admission).isInstanceOf(Limited.class));
        assertThat(redis.opsForValue().get(failuresKey(IDENTITY))).as("failures").isEqualTo("5");
        assertThat(redis.opsForValue().get(addressKey(ADDRESS)))
                .as("attempts of the address")
                .isEqualTo(address);
        assertThat(delayLeft(IDENTITY)).as("delay left, in ms").isBetween(1L, 60_000L);
    }

    @Test
    void everyAdmittedAttemptCountsInTheAddress() {
        IntStream.range(0, 3).forEach(i -> begin("identity-" + i, ADDRESS, LIMITS));

        assertThat(redis.opsForValue().get(addressKey(ADDRESS)))
                .as("attempts of the address")
                .isEqualTo("3");
    }

    @Test
    void addressAtItsLimitRefusesAndLeavesNoKeyOfTheIdentity() {
        var limits = new LoginLimits(2, WINDOW, DEFAULTS);
        begin("identity-0", ADDRESS, limits);
        begin("identity-1", ADDRESS, limits);

        var admission = begin("identity-new", ADDRESS, limits);

        assertThat(admission).as("admission").isInstanceOf(Limited.class);
        assertThat(redis.hasKey(failuresKey("identity-new"))).as("failures key").isFalse();
        assertThat(redis.hasKey(delayKey("identity-new"))).as("delay key").isFalse();
    }

    @Test
    void identityVariedFromOneAddressCreatesAtMostOneKeyPerAdmittedAttempt() {
        var limits = new LoginLimits(5, WINDOW, DEFAULTS);

        IntStream.range(0, 40).forEach(i -> begin("identity-" + i, ADDRESS, limits));

        assertThat(redis.keys("auth:limits:login:failures:*")).as("failure keys").hasSize(5);
    }

    @Test
    void successForgetsFailuresAndDelayButKeepsTheAddress() {
        failures(IDENTITY, 4);
        begin(IDENTITY, ADDRESS, LIMITS);

        var recorded = limiter.forget(IDENTITY);

        assertThat(recorded).as("recorded").isTrue();
        assertThat(redis.hasKey(failuresKey(IDENTITY))).as("failures key").isFalse();
        assertThat(redis.hasKey(delayKey(IDENTITY))).as("delay key").isFalse();
        assertThat(redis.opsForValue().get(addressKey(ADDRESS)))
                .as("attempts of the address")
                .isEqualTo("1");
    }

    @Test
    void attemptThatIsNeverFinishedKeepsItsFailure() {
        failures(IDENTITY, 2);

        begin(IDENTITY, ADDRESS, LIMITS);

        assertThat(redis.opsForValue().get(failuresKey(IDENTITY)))
                .as("failures, the interrupted attempt included")
                .isEqualTo("3");
    }

    @Test
    void forgetOfALoneAttemptLeavesNoKeyOfTheIdentityBehind() {
        begin(IDENTITY, ADDRESS, LIMITS);

        limiter.forget(IDENTITY);

        assertThat(redis.keys("auth:limits:login:*")).as("keys of the login").isEmpty();
    }

    @Test
    void growthFactorThatIsNotAnIntegerGivesTheDelayRoundedDownToTheMillisecond() {
        var limits =
                new LoginLimits(
                        30,
                        WINDOW,
                        new FailureDelay(
                                5,
                                Duration.ofSeconds(5),
                                1.5,
                                Duration.ofMinutes(5),
                                Duration.ofMinutes(30)));
        failures(IDENTITY, 6);

        begin(IDENTITY, ADDRESS, limits);

        // 5 s * 1.5^2 = 11.25 s
        assertThat(delayLeft(IDENTITY)).as("delay left, in ms").isBetween(9_250L, 11_250L);
    }

    @Test
    void forgetForgetsFailuresAndDelayOfTheIdentityButKeepsTheAddress() {
        failures(IDENTITY, 4);
        begin(IDENTITY, ADDRESS, LIMITS);
        assertThat(begin(IDENTITY, ADDRESS, LIMITS))
                .as("given: the identity is in its delay")
                .isInstanceOf(Limited.class);

        var recorded = limiter.forget(IDENTITY);

        assertThat(recorded).as("recorded").isTrue();
        assertThat(redis.hasKey(failuresKey(IDENTITY))).as("failures key").isFalse();
        assertThat(redis.hasKey(delayKey(IDENTITY))).as("delay key").isFalse();
        assertThat(redis.opsForValue().get(addressKey(ADDRESS)))
                .as("attempts of the address")
                .isEqualTo("1");
    }

    @Test
    void identityIsAdmittedAtOnceAfterItsForget() {
        failures(IDENTITY, 4);
        begin(IDENTITY, ADDRESS, LIMITS);

        limiter.forget(IDENTITY);

        assertThat(begin(IDENTITY, ADDRESS, LIMITS))
                .as("admission")
                .isInstanceOf(Admitted.class);
        assertThat(redis.opsForValue().get(failuresKey(IDENTITY)))
                .as("failures counted again from the start")
                .isEqualTo("1");
    }

    @Test
    void forgetOfOneIdentityLeavesTheCountersOfAnotherIdentity() {
        failures(IDENTITY, 4);
        failures("jeune", 4);
        begin("jeune", ADDRESS, LIMITS);

        limiter.forget(IDENTITY);

        assertThat(redis.opsForValue().get(failuresKey("jeune")))
                .as("failures of the other identity")
                .isEqualTo("5");
        assertThat(redis.hasKey(delayKey("jeune"))).as("delay of the other identity").isTrue();
    }

    @Test
    void forgetRemovesADelayThatHasNoFailures() {
        redis.opsForValue().set(delayKey(IDENTITY), "orphan", Duration.ofSeconds(60));

        var recorded = limiter.forget(IDENTITY);

        assertThat(recorded).as("recorded").isTrue();
        assertThat(redis.keys("auth:limits:login:*")).as("keys of the login").isEmpty();
    }

    @Test
    void forgetOfAnIdentityWithoutCountersLeavesNoKeyBehind() {
        var recorded = limiter.forget(IDENTITY);

        assertThat(recorded).as("recorded").isTrue();
        assertThat(redis.keys("auth:limits:login:*")).as("keys of the login").isEmpty();
    }

    @Test
    void parallelAttemptsAreAdmittedUpToTheFreeFailuresAndNoFurther() throws Exception {
        var admissions = inParallel(50, i -> begin(IDENTITY, "198.51.100." + i, LIMITS));

        assertThat(admissions.stream().filter(Admitted.class::isInstance).count())
                .as("admitted")
                .isEqualTo(5);
        assertThat(admissions.stream().filter(Limited.class::isInstance).count())
                .as("limited")
                .isEqualTo(45);
        assertThat(redis.opsForValue().get(failuresKey(IDENTITY))).as("failures").isEqualTo("5");
    }

    @Test
    void failuresLiveAsLongAsTheCounterTtlAndTheTtlIsRenewedByEachFailure() {
        var limits =
                new LoginLimits(
                        30,
                        WINDOW,
                        new FailureDelay(
                                5,
                                Duration.ofSeconds(5),
                                2.0,
                                Duration.ofMinutes(5),
                                Duration.ofMinutes(20)));
        begin(IDENTITY, ADDRESS, limits);
        assertThat(redis.getExpire(failuresKey(IDENTITY)))
                .as("life, in seconds")
                .isBetween(1_190L, 1_200L);
        redis.expire(failuresKey(IDENTITY), Duration.ofSeconds(30));

        begin(IDENTITY, ADDRESS, limits);

        assertThat(redis.getExpire(failuresKey(IDENTITY)))
                .as("life after a failure, in seconds")
                .isBetween(1_190L, 1_200L);
    }

    @Test
    void addressCounterLivesTheWindowOfTheLogin() {
        begin(IDENTITY, ADDRESS, new LoginLimits(30, Duration.ofMinutes(2), DEFAULTS));

        assertThat(redis.getExpire(addressKey(ADDRESS)))
                .as("life, in seconds")
                .isBetween(110L, 120L);
    }

    @Test
    void everyKeyHasALifeWhateverTheInterleaving() throws Exception {
        inParallel(60, i -> begin("identity-" + (i % 3), "198.51.100." + (i % 4), LIMITS));

        var lives =
                redis.keys("auth:limits:*").stream()
                        .filter(key -> !key.equals("auth:limits:witness"))
                        .map(redis::getExpire)
                        .toList();
        assertThat(lives)
                .as("lives, in seconds")
                .isNotEmpty()
                .allSatisfy(life -> assertThat(life).isPositive());
    }

    @Test
    void witnessLostIsReportedOnceByTheLogin() {
        double before = meters.get("auth.rate_limit.counters.lost").counter().count();
        redis.delete("auth:limits:witness");

        begin(IDENTITY, ADDRESS, LIMITS);
        begin(IDENTITY, ADDRESS, LIMITS);

        assertThat(meters.get("auth.rate_limit.counters.lost").counter().count() - before)
                .as("losses")
                .isEqualTo(1);
    }
}
