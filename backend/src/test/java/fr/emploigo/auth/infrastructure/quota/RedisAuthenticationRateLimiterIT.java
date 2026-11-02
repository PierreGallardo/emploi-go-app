package fr.emploigo.auth.infrastructure.quota;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import ch.qos.logback.classic.Level;

import fr.emploigo.auth.application.AuthenticationIntegrationTest;
import fr.emploigo.auth.application.quota.AuthenticationRateLimiter.Allowed;
import fr.emploigo.auth.application.quota.AuthenticationRateLimiter.Cause;
import fr.emploigo.auth.application.quota.AuthenticationRateLimiter.Limited;
import fr.emploigo.auth.application.quota.AuthenticationRateLimiter.Outcome;
import fr.emploigo.auth.application.quota.IdentityLimits;
import fr.emploigo.auth.application.quota.QuotaLimits;
import fr.emploigo.auth.application.quota.QuotaLimits.MailLimits;
import fr.emploigo.auth.application.quota.QuotaScope;
import fr.emploigo.support.LogCapture;

import io.micrometer.core.instrument.MeterRegistry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.IntFunction;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * The script of the counters against a real Redis: what it counts, in which order, and what it
 * leaves behind.
 */
class RedisAuthenticationRateLimiterIT extends AuthenticationIntegrationTest {
    private static final String ADDRESS = "203.0.113.7";
    private static final Duration WINDOW = Duration.ofMinutes(15);
    private static final MailLimits MAIL = new MailLimits(Duration.ofSeconds(60), 3, 1000);

    @Autowired MeterRegistry meters;

    private static QuotaLimits limits(QuotaScope scope, int address, int identity) {
        return new QuotaLimits(address, identity, WINDOW, scope.mail() ? MAIL : null);
    }

    private Outcome check(QuotaScope scope, String identity, String address, QuotaLimits limits) {
        return limiter.check(scope, identity, address, limits);
    }

    private int keys(String pattern) {
        return redis.keys(pattern).size();
    }

    private double exceeded(QuotaScope scope) {
        var counter =
                meters.find("auth.rate_limit.global.exceeded").tag("scope", scope.key()).counter();
        return counter == null ? 0 : counter.count();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(
            value = QuotaScope.class,
            mode = EnumSource.Mode.EXCLUDE,
            names = {"LOGIN", "RESUME"})
    void identityOfARequestRefusedByTheAddressCreatesNoKey(QuotaScope scope) {
        var limits = limits(scope, 2, 5);
        check(scope, "identity-0", ADDRESS, limits);
        check(scope, "identity-1", ADDRESS, limits);
        int before = keys("auth:limits:*identity*");

        var outcomes =
                IntStream.range(2, 22)
                        .mapToObj(i -> check(scope, "identity-" + i, ADDRESS, limits))
                        .toList();

        assertThat(outcomes)
                .as("outcomes")
                .hasSize(20)
                .allMatch(outcome -> outcome instanceof Limited);
        assertThat(keys("auth:limits:*identity*")).as("identity keys").isEqualTo(before);
    }

    /**
     * The /60 number {@code index} (0 to 4095) of the /48 {@code 2001:db8:1::/48}, as {@code
     * ClientNetwork} writes it.
     */
    private static String network(int index) {
        return String.format("v6:20010db80001%03x", index);
    }

    private static final String PREFIX_KEY =
            "auth:limits:registration-mail:prefix48:"
                    + TestQuotaKeys.KEYS.prefix48("v6:20010db80001");

    private static QuotaLimits withPrefixLimit(int prefix) {
        return new QuotaLimits(
                100, 100, WINDOW, new MailLimits(Duration.ofSeconds(1), 1000, prefix));
    }

    @Test
    void networksOfOnePrefixShareTheLimitOfThePrefixAndAreRefusedAboveIt() {
        var scope = QuotaScope.REGISTRATION_MAIL;
        var limits = withPrefixLimit(3);

        var outcomes =
                IntStream.range(0, 5)
                        .mapToObj(
                                index -> check(scope, "identity-" + index, network(index), limits))
                        .toList();

        assertThat(outcomes.subList(0, 3))
                .as("within the limit of the prefix")
                .containsOnly(new Allowed());
        assertThat(outcomes.subList(3, 5))
                .as("above the limit of the prefix")
                .allMatch(outcome -> outcome instanceof Limited);
    }

    @Test
    void identityVariedOverOnePrefixCreatesAtMostOneKeyPerNetworkAtLimitOne() {
        var scope = QuotaScope.RECOVERY_CONFIRM;
        var limits = limits(scope, 1, 100);

        IntStream.range(0, 8192)
                .forEach(
                        index ->
                                check(scope, "identity-" + index, network(index % 4096), limits));

        assertThat(keys("auth:limits:recovery-confirm:identity:*"))
                .as("identity keys")
                .isEqualTo(4096);
    }

    @Test
    void prefixRefusalAnnouncesTheWaitOfTheCounterOfThePrefix() {
        var scope = QuotaScope.REGISTRATION_MAIL;
        var limits =
                new QuotaLimits(
                        100,
                        100,
                        Duration.ofMinutes(2),
                        new MailLimits(Duration.ofSeconds(1), 1000, 1));
        check(scope, "identity-0", network(0), limits);

        var outcome = check(scope, "identity-1", network(1), limits);

        assertThat(outcome)
                .as("outcome")
                .isInstanceOfSatisfying(
                        Limited.class,
                        limited ->
                                assertThat(limited.retryAfter())
                                        .as("retry after")
                                        .isPositive()
                                        .isLessThanOrEqualTo(Duration.ofMinutes(2)));
    }

    @Test
    void anotherPrefixHasItsOwnCounter() {
        var scope = QuotaScope.REGISTRATION_MAIL;
        var limits = withPrefixLimit(1);
        check(scope, "identity-0", network(0), limits);

        var outcome = check(scope, "identity-1", "v6:20010db80002000", limits);

        assertThat(outcome).as("outcome").isEqualTo(new Allowed());
    }

    @Test
    void requestRefusedByThePrefixSetsNoDelayAndAddsNothingToTheGlobalCount() {
        var scope = QuotaScope.REGISTRATION_MAIL;
        var limits = withPrefixLimit(1);
        check(scope, "identity-0", network(0), limits);
        int cooldowns = keys("auth:limits:registration-mail:cooldown:*");
        var global = redis.opsForValue().get("auth:limits:registration-mail:global");

        var outcomes =
                IntStream.range(1, 21)
                        .mapToObj(
                                index -> check(scope, "identity-" + index, network(index), limits))
                        .toList();

        assertThat(outcomes).as("outcomes").allMatch(outcome -> outcome instanceof Limited);
        assertThat(keys("auth:limits:registration-mail:cooldown:*"))
                .as("delays")
                .isEqualTo(cooldowns);
        assertThat(redis.opsForValue().get("auth:limits:registration-mail:global"))
                .as("global count")
                .isEqualTo(global);
    }

    @Test
    void requestRefusedByAFullPrefixCreatesNoKeyOfIdentity() {
        var scope = QuotaScope.REGISTRATION_MAIL;
        var limits = withPrefixLimit(2);
        check(scope, "identity-0", network(0), limits);
        check(scope, "identity-1", network(1), limits);
        int identities = keys("auth:limits:registration-mail:identity:*");

        var outcomes =
                IntStream.range(2, 22)
                        .mapToObj(
                                index -> check(scope, "identity-" + index, network(index), limits))
                        .toList();

        assertThat(outcomes).as("outcomes").allMatch(outcome -> outcome instanceof Limited);
        assertThat(keys("auth:limits:registration-mail:identity:*"))
                .as("identity keys")
                .isEqualTo(identities);
    }

    @Test
    void requestRefusedByAFullPrefixDoesNotUseTheTriesOfTheIdentityOfTheYoungPerson() {
        var scope = QuotaScope.REGISTRATION_MAIL;
        var limits =
                new QuotaLimits(100, 2, WINDOW, new MailLimits(Duration.ofSeconds(1), 1000, 1));
        check(scope, "identity-0", network(0), limits);
        IntStream.range(0, 3).forEach(_ -> check(scope, "identity-1", network(1), limits));
        redis.delete(PREFIX_KEY);

        var outcome = check(scope, "identity-1", network(1), limits);

        assertThat(outcome).as("outcome once the prefix is free").isEqualTo(new Allowed());
    }

    @Test
    void requestRefusedByTheAddressDoesNotCountInThePrefix() {
        var scope = QuotaScope.REGISTRATION_MAIL;
        var limits =
                new QuotaLimits(1, 100, WINDOW, new MailLimits(Duration.ofSeconds(1), 1000, 1000));
        check(scope, "identity-0", network(0), limits);
        double refusalsByPrefix = prefixRefusals(scope);

        var outcomes =
                IntStream.range(1, 6)
                        .mapToObj(index -> check(scope, "identity-" + index, network(0), limits))
                        .toList();

        assertThat(outcomes).as("outcomes").allMatch(outcome -> outcome instanceof Limited);
        assertThat(redis.opsForValue().get(PREFIX_KEY)).as("counter of the prefix").isEqualTo("1");
        assertThat(prefixRefusals(scope) - refusalsByPrefix)
                .as("refusals by prefix")
                .isZero();
    }

    private double prefixRefusals(QuotaScope scope) {
        return meters.get("auth.rate_limit.prefix.limited")
                .tag("scope", scope.key())
                .counter()
                .count();
    }

    @Test
    void requestRefusedByTheIdentityDoesNotCountInThePrefixSinceItSendsNothing() {
        var scope = QuotaScope.REGISTRATION_MAIL;
        var limits =
                new QuotaLimits(100, 1, WINDOW, new MailLimits(Duration.ofSeconds(1), 1000, 1000));
        check(scope, "identity-0", network(0), limits);

        var outcome = check(scope, "identity-0", network(1), limits);

        assertThat(outcome).as("outcome").isInstanceOf(Limited.class);
        assertThat(redis.opsForValue().get(PREFIX_KEY)).as("counter of the prefix").isEqualTo("1");
    }

    @Test
    void requestRefusedByTheDelayDoesNotCountInThePrefixSinceItSendsNothing() {
        var scope = QuotaScope.REGISTRATION_MAIL;
        var limits =
                new QuotaLimits(
                        100, 100, WINDOW, new MailLimits(Duration.ofMinutes(5), 1000, 1000));
        check(scope, "identity-0", network(0), limits);

        var outcome = check(scope, "identity-0", network(1), limits);

        assertThat(outcome).as("outcome").isInstanceOf(Limited.class);
        assertThat(redis.opsForValue().get(PREFIX_KEY)).as("counter of the prefix").isEqualTo("1");
    }

    @Test
    void ipv4ClientHasNoCounterOfPrefixSoItIsNeverRefusedByOne() {
        var scope = QuotaScope.REGISTRATION_MAIL;

        var outcomes =
                IntStream.range(0, 5)
                        .mapToObj(
                                index ->
                                        check(
                                                scope,
                                                "identity-" + index,
                                                "198.51.100." + index,
                                                withPrefixLimit(1)))
                        .toList();

        assertThat(outcomes).as("outcomes").containsOnly(new Allowed());
        assertThat(keys("auth:limits:*:prefix48:*")).as("keys of prefix").isZero();
        assertThat(redis.hasKey("auth:limits:no-prefix")).as("stand-in counter").isFalse();
    }

    @Test
    void actionThatSendsNoEmailHasNoPrefixNorDelayNorGlobalCount() {
        var scope = QuotaScope.RECOVERY_CONFIRM;

        IntStream.range(0, 5)
                .forEach(
                        index ->
                                check(
                                        scope,
                                        "identity-" + index,
                                        network(index),
                                        limits(scope, 100, 100)));

        assertThat(keys("auth:limits:*:prefix48:*")).as("keys of prefix").isZero();
        assertThat(keys("auth:limits:recovery-confirm:cooldown:*")).as("delays").isZero();
        assertThat(redis.hasKey("auth:limits:recovery-confirm:global"))
                .as("global count")
                .isFalse();
    }

    @Test
    void counterOfThePrefixLivesTheWindowOfTheAction() {
        check(
                QuotaScope.REGISTRATION_MAIL,
                "identity-0",
                network(0),
                new QuotaLimits(
                        100,
                        100,
                        Duration.ofMinutes(3),
                        new MailLimits(Duration.ofSeconds(1), 1000, 5)));

        var life = redis.getExpire(PREFIX_KEY);

        assertThat(life).as("seconds left").isPositive().isLessThanOrEqualTo(180);
    }

    @Test
    void identityAloneCreatesTheWitnessWhenRedisLostItsCountersAndReportsTheLoss() {
        redis.delete(RedisAuthenticationRateLimiter.WITNESS_KEY);
        double before = meters.get("auth.rate_limit.counters.lost").counter().count();

        limiter.checkIdentity(QuotaScope.RESUME, "family-1", new IdentityLimits(5, WINDOW));

        assertThat(redis.hasKey(RedisAuthenticationRateLimiter.WITNESS_KEY)).as("witness").isTrue();
        assertThat(meters.get("auth.rate_limit.counters.lost").counter().count() - before)
                .as("losses reported")
                .isEqualTo(1);
    }

    @Test
    void identityAloneGivesALifeToACounterThatHasNoneAndAnnouncesTheWait() {
        String key = "auth:limits:resume:identity:" + TestQuotaKeys.KEYS.identity("family-1");
        redis.opsForValue().set(key, "5");

        var outcome =
                limiter.checkIdentity(QuotaScope.RESUME, "family-1", new IdentityLimits(2, WINDOW));

        assertThat(outcome)
                .as("outcome")
                .isInstanceOfSatisfying(
                        Limited.class,
                        limited ->
                                assertThat(limited.retryAfter())
                                        .as("retry after")
                                        .isPositive()
                                        .isLessThanOrEqualTo(WINDOW));
        assertThat(redis.getExpire(key)).as("seconds left").isPositive();
    }

    @Test
    void identityAloneIsAllowedUpToItsLimitThenRefusedWithTheWaitOfItsWindow() {
        var scope = QuotaScope.RESUME;
        var limits = new IdentityLimits(2, Duration.ofMinutes(2));

        var outcomes =
                IntStream.range(0, 4)
                        .mapToObj(_ -> limiter.checkIdentity(scope, "family-1", limits))
                        .toList();

        assertThat(outcomes).as("outcomes").hasSize(4).first().isEqualTo(new Allowed());
        assertThat(outcomes.get(1)).as("second").isEqualTo(new Allowed());
        assertThat(outcomes.get(2))
                .as("third")
                .isInstanceOfSatisfying(
                        Limited.class,
                        limited ->
                                assertThat(limited.retryAfter())
                                        .as("retry after")
                                        .isPositive()
                                        .isLessThanOrEqualTo(Duration.ofMinutes(2)));
        assertThat(outcomes.get(3)).as("fourth").isInstanceOf(Limited.class);
    }

    @Test
    void identityAloneCountsEachIdentityOnItsOwnAndCreatesNoKeyOfAnAddress() {
        var limits = new IdentityLimits(1, WINDOW);

        var first = limiter.checkIdentity(QuotaScope.RESUME, "family-1", limits);
        var second = limiter.checkIdentity(QuotaScope.RESUME, "family-2", limits);
        var repeated = limiter.checkIdentity(QuotaScope.RESUME, "family-1", limits);

        assertThat(first).as("first identity").isEqualTo(new Allowed());
        assertThat(second).as("second identity").isEqualTo(new Allowed());
        assertThat(repeated).as("first identity again").isInstanceOf(Limited.class);
        assertThat(keys("auth:limits:resume:identity:*")).as("identity keys").isEqualTo(2);
        assertThat(keys("auth:limits:resume:ip:*")).as("address keys").isZero();
        assertThat(keys("auth:limits:ip:*")).as("address keys of the login").isZero();
    }

    @Test
    void identityAloneCounterExpiresWithTheWindow() {
        limiter.checkIdentity(
                QuotaScope.RESUME, "family-1", new IdentityLimits(5, Duration.ofMinutes(3)));

        var life =
                redis.getExpire(
                        "auth:limits:resume:identity:" + TestQuotaKeys.KEYS.identity("family-1"));

        assertThat(life).as("seconds left").isPositive().isLessThanOrEqualTo(180);
    }

    @Test
    void emailAlreadySentWithinTheDelayIsRefusedBecauseOfTheDelay() {
        var scope = QuotaScope.REGISTRATION_MAIL;
        var limits = limits(scope, 10, 5);
        check(scope, "identity-0", ADDRESS, limits);

        var outcome = check(scope, "identity-0", ADDRESS, limits);

        assertThat(outcome)
                .as("outcome")
                .isInstanceOfSatisfying(
                        Limited.class,
                        limited -> {
                            assertThat(limited.cause()).as("cause").isEqualTo(Cause.SEND_DELAY);
                            assertThat(limited.retryAfter())
                                    .as("retry after")
                                    .isPositive()
                                    .isLessThanOrEqualTo(MAIL.sendDelay());
                        });
    }

    @Test
    void identityLimitReachedWithoutActiveDelayIsRefusedBecauseOfTheVolume() {
        var scope = QuotaScope.REGISTRATION_MAIL;
        var limits = limits(scope, 10, 1);
        check(scope, "identity-0", ADDRESS, limits);
        permitNextMail(scope.key(), "identity-0");

        var outcome = check(scope, "identity-0", ADDRESS, limits);

        assertThat(outcome)
                .as("outcome")
                .isInstanceOfSatisfying(
                        Limited.class,
                        limited -> assertThat(limited.cause()).as("cause").isEqualTo(Cause.VOLUME));
    }

    @Test
    void requestsRefusedByTheDelayCostTheIdentityNoneOfItsSends() {
        var scope = QuotaScope.REGISTRATION_MAIL;
        var limits = limits(scope, 10, 3);
        check(scope, "identity-0", ADDRESS, limits);
        var refused =
                List.of(
                        check(scope, "identity-0", ADDRESS, limits),
                        check(scope, "identity-0", ADDRESS, limits));
        permitNextMail(scope.key(), "identity-0");

        var outcome = check(scope, "identity-0", ADDRESS, limits);

        assertThat(refused)
                .as("given: refused by the delay")
                .allSatisfy(
                        limited ->
                                assertThat(limited)
                                        .isInstanceOfSatisfying(
                                                Limited.class,
                                                it ->
                                                        assertThat(it.cause())
                                                                .isEqualTo(Cause.SEND_DELAY)));
        assertThat(outcome).as("outcome").isInstanceOf(Allowed.class);
        assertThat(
                        redis.opsForValue()
                                .get(
                                        "auth:limits:registration-mail:ip:"
                                                + TestQuotaKeys.KEYS.address(ADDRESS)))
                .as("attempts of the address, refusals included")
                .isEqualTo("4");
    }

    @Test
    void addressLimitReachedIsRefusedBecauseOfTheVolume() {
        var scope = QuotaScope.REGISTRATION_MAIL;
        var limits = limits(scope, 1, 5);
        check(scope, "identity-0", ADDRESS, limits);

        var outcome = check(scope, "identity-1", ADDRESS, limits);

        assertThat(outcome)
                .as("outcome")
                .isInstanceOfSatisfying(
                        Limited.class,
                        limited -> assertThat(limited.cause()).as("cause").isEqualTo(Cause.VOLUME));
    }

    @Test
    void emailRequestRefusedByTheAddressLeavesNoCooldown() {
        var scope = QuotaScope.REGISTRATION_MAIL;
        var limits = limits(scope, 1, 5);
        check(scope, "identity-0", ADDRESS, limits);

        var outcome = check(scope, "identity-1", ADDRESS, limits);

        assertThat(outcome).as("outcome").isInstanceOf(Limited.class);
        assertThat(keys("auth:limits:registration-mail:cooldown:*")).as("cooldowns").isEqualTo(1);
    }

    @Test
    void emailRequestRefusedByTheAddressDoesNotAdvanceTheGlobalCounter() {
        var scope = QuotaScope.REGISTRATION_MAIL;
        var limits = limits(scope, 1, 5);
        check(scope, "identity-0", ADDRESS, limits);
        var global = redis.opsForValue().get("auth:limits:registration-mail:global");

        check(scope, "identity-1", ADDRESS, limits);

        assertThat(redis.opsForValue().get("auth:limits:registration-mail:global"))
                .as("global counter")
                .isEqualTo(global);
    }

    @Test
    void identityRefusalStillCountsInTheAddress() {
        var scope = QuotaScope.RECOVERY_CONFIRM;
        var limits = limits(scope, 5, 1);
        check(scope, "identity-0", ADDRESS, limits);

        var outcome = check(scope, "identity-0", ADDRESS, limits);

        assertThat(outcome).as("outcome").isInstanceOf(Limited.class);
        assertThat(
                        redis.opsForValue()
                                .get(
                                        "auth:limits:recovery-confirm:ip:"
                                                + TestQuotaKeys.KEYS.address(ADDRESS)))
                .as("address counter")
                .isEqualTo("2");
    }

    @Test
    void addressRefusalAnnouncesTheWaitOfTheAddress() {
        var scope = QuotaScope.RECOVERY_CONFIRM;
        var limits = new QuotaLimits(1, 5, Duration.ofMinutes(2), null);
        check(scope, "identity-0", ADDRESS, limits);

        var outcome = check(scope, "identity-1", ADDRESS, limits);

        assertThat(outcome)
                .as("outcome")
                .isInstanceOfSatisfying(
                        Limited.class,
                        limited ->
                                assertThat(limited.retryAfter())
                                        .as("retry after")
                                        .isPositive()
                                        .isLessThanOrEqualTo(Duration.ofMinutes(2)));
    }

    @Test
    void emailsAboveTheGlobalThresholdPassAndAreCounted() {
        var scope = QuotaScope.REGISTRATION_MAIL;
        var limits = limits(scope, 100, 100);
        double before = exceeded(scope);

        var outcomes =
                IntStream.range(0, 6)
                        .mapToObj(i -> check(scope, "identity-" + i, "198.51.100." + i, limits))
                        .toList();

        assertThat(outcomes).as("outcomes").containsOnly(new Allowed());
        assertThat(exceeded(scope) - before).as("sends above the threshold").isEqualTo(3);
    }

    @Test
    void emailAboveTheGlobalThresholdWritesOneWarningPerMinuteWithoutIdentityOrAddress() {
        var scope = QuotaScope.REGISTRATION_MAIL;
        var limits = limits(scope, 100, 100);
        IntStream.range(0, 3)
                .forEach(i -> check(scope, "identity-" + i, "198.51.100." + i, limits));
        clock.now = clock.now.plus(Duration.ofHours(1));

        try (var logs = LogCapture.of(RedisAuthenticationRateLimiter.class)) {
            check(scope, "jeune@example.invalid", "198.51.100.200", limits);
            check(scope, "autre@example.invalid", "198.51.100.201", limits);

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
                                        .doesNotContain("198.51.100");
                            });
        }
    }

    @Test
    void delayBetweenTwoSendsStillRefusesAboveTheGlobalThreshold() {
        var scope = QuotaScope.REGISTRATION_MAIL;
        var limits = limits(scope, 100, 100);
        IntStream.range(0, 5)
                .forEach(i -> check(scope, "identity-" + i, "198.51.100." + i, limits));

        var outcome = check(scope, "identity-0", "198.51.100.99", limits);

        assertThat(outcome)
                .as("outcome")
                .isInstanceOfSatisfying(
                        Limited.class,
                        limited ->
                                assertThat(limited.retryAfter())
                                        .as("retry after")
                                        .isPositive()
                                        .isLessThanOrEqualTo(MAIL.sendDelay()));
    }

    @Test
    void addressLimitStillRefusesAboveTheGlobalThreshold() {
        var scope = QuotaScope.REGISTRATION_MAIL;
        var limits = limits(scope, 4, 100);
        IntStream.range(0, 4).forEach(i -> check(scope, "identity-" + i, ADDRESS, limits));

        var outcome = check(scope, "identity-new", ADDRESS, limits);

        assertThat(outcome).as("outcome").isInstanceOf(Limited.class);
    }

    @Test
    void identityLimitStillRefusesAboveTheGlobalThreshold() {
        var scope = QuotaScope.GOOGLE_MAIL;
        var limits =
                new QuotaLimits(100, 2, WINDOW, new MailLimits(Duration.ofSeconds(1), 1, 1000));
        check(scope, "identity", "198.51.100.1", limits);
        permitNextMail(scope.key(), "identity");
        check(scope, "identity", "198.51.100.2", limits);
        permitNextMail(scope.key(), "identity");

        var outcome = check(scope, "identity", "198.51.100.3", limits);

        assertThat(outcome).as("outcome").isInstanceOf(Limited.class);
    }

    static Stream<Arguments> windows() {
        return Stream.of(
                argumentSet("deux minutes", Duration.ofMinutes(2)),
                argumentSet("une heure", Duration.ofHours(1)));
    }

    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("windows")
    void windowOfTheLimitsIsTheLifeOfTheCounters(Duration window) {
        var scope = QuotaScope.REGISTRATION_MAIL;
        check(scope, "identity", ADDRESS, new QuotaLimits(10, 3, window, MAIL));

        var lives =
                Stream.of(
                                "auth:limits:registration-mail:ip:"
                                        + TestQuotaKeys.KEYS.address(ADDRESS),
                                "auth:limits:registration-mail:identity:"
                                        + TestQuotaKeys.KEYS.identity("identity"),
                                "auth:limits:registration-mail:global")
                        .map(redis::getExpire)
                        .toList();

        assertThat(lives)
                .as("lives, in seconds")
                .allSatisfy(
                        life ->
                                assertThat(life)
                                        .isBetween(window.toSeconds() - 10, window.toSeconds()));
    }

    @Test
    void sendDelayOfTheLimitsIsTheLifeOfTheCooldown() {
        var scope = QuotaScope.RECOVERY_MAIL;
        check(
                scope,
                "identity",
                ADDRESS,
                new QuotaLimits(10, 3, WINDOW, new MailLimits(Duration.ofSeconds(45), 300, 1000)));

        var life =
                redis.getExpire(
                        "auth:limits:recovery-mail:cooldown:"
                                + TestQuotaKeys.KEYS.identity("identity"));

        assertThat(life).as("life, in seconds").isBetween(35L, 45L);
    }

    private List<Outcome> concurrently(int calls, IntFunction<Outcome> call) {
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var gate = new CountDownLatch(1);
            var outcomes =
                    IntStream.range(0, calls)
                            .mapToObj(
                                    i ->
                                            pool.submit(
                                                    () -> {
                                                        gate.await();
                                                        return call.apply(i);
                                                    }))
                            .toList();
            gate.countDown();
            return outcomes.stream().map(unchecked(Future::get)).toList();
        }
    }

    @Test
    void simultaneousSendsToOneIdentityLetExactlyOneThrough() {
        var scope = QuotaScope.REGISTRATION_MAIL;
        var limits = limits(scope, 100, 100);

        var outcomes =
                concurrently(20, i -> check(scope, "same-identity", "198.51.100." + i, limits));

        assertThat(outcomes.stream().filter(outcome -> outcome instanceof Allowed).count())
                .as("sends")
                .isEqualTo(1);
        assertThat(keys("auth:limits:registration-mail:cooldown:*")).as("cooldowns").isEqualTo(1);
    }

    @Test
    void simultaneousAttemptsFromOneAddressStopAtItsLimit() {
        var scope = QuotaScope.RECOVERY_CONFIRM;
        var limits = limits(scope, 5, 100);

        var outcomes = concurrently(30, i -> check(scope, "identity-" + i, ADDRESS, limits));

        assertThat(outcomes.stream().filter(outcome -> outcome instanceof Allowed).count())
                .as("allowed")
                .isEqualTo(5);
        assertThat(keys("auth:limits:recovery-confirm:identity:*"))
                .as("identity keys")
                .isEqualTo(5);
    }

    @Test
    void everyCounterHasALifeWhateverTheInterleaving() {
        var scope = QuotaScope.REGISTRATION_MAIL;
        var limits = limits(scope, 3, 3);

        concurrently(30, i -> check(scope, "identity-" + (i % 4), "198.51.100." + (i % 3), limits));

        var lives =
                redis.keys("auth:limits:*").stream()
                        .filter(key -> !key.equals(RedisAuthenticationRateLimiter.WITNESS_KEY))
                        .map(redis::getExpire)
                        .toList();
        assertThat(lives)
                .as("lives, in seconds")
                .isNotEmpty()
                .allSatisfy(life -> assertThat(life).isPositive());
    }
}
