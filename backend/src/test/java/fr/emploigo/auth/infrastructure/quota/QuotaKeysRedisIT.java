package fr.emploigo.auth.infrastructure.quota;

import static org.assertj.core.api.Assertions.assertThat;

import fr.emploigo.auth.application.AuthenticationIntegrationTest;
import fr.emploigo.auth.application.quota.AuthenticationRateLimiter;
import fr.emploigo.auth.application.quota.IdentityLimits;
import fr.emploigo.auth.application.quota.LoginAttemptLimiter.Limited;
import fr.emploigo.auth.application.quota.LoginAttemptLimiter;
import fr.emploigo.auth.application.quota.QuotaLimits.MailLimits;
import fr.emploigo.auth.application.quota.QuotaLimits;
import fr.emploigo.auth.application.quota.QuotaScope;
import fr.emploigo.auth.domain.AuthenticationSecrets;

import io.micrometer.core.instrument.MeterRegistry;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.env.MockEnvironment;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * What is in Redis after every kind of counter has been written: no plain digest of an identity or
 * of an address.
 */
class QuotaKeysRedisIT extends AuthenticationIntegrationTest {
    private static final String EMAIL = "email:jeune@example.invalid";
    private static final String OTHER_EMAIL = "email:autre@example.invalid";
    private static final String LOGIN = "jeune@example.invalid";
    private static final String DELAYED = "delaye@example.invalid";
    private static final String IPV4 = "203.0.113.7";
    private static final String IPV6 = "v6:20010db80001000";
    private static final String PREFIX_48 = "v6:20010db80001";
    private static final String FAMILY = "family-1";
    private static final QuotaLimits MAIL =
            new QuotaLimits(
                    100,
                    100,
                    Duration.ofMinutes(15),
                    new MailLimits(Duration.ofSeconds(60), 1000, 5));

    @Autowired AuthenticationRateLimiter limiter;
    @Autowired LoginAttemptLimiter login;
    @Autowired MeterRegistry meters;
    @Autowired StringRedisTemplate template;

    private Set<String> keysAfterEveryCounter() {
        limiter.check(QuotaScope.REGISTRATION_MAIL, EMAIL, IPV4, MAIL);
        limiter.check(QuotaScope.REGISTRATION_MAIL, OTHER_EMAIL, IPV6, MAIL);
        limiter.checkIdentity(
                QuotaScope.RESUME, FAMILY, new IdentityLimits(5, Duration.ofMinutes(15)));
        var limits = QuotaTestProperties.loginLimits();
        for (var network : List.of(IPV4, IPV6)) login.begin(LOGIN, network, limits);
        untilDelayed(DELAYED, limits);
        return redis.keys("auth:limits:*");
    }

    private void untilDelayed(String identity, fr.emploigo.auth.application.quota.LoginLimits limits) {
        for (int attempt = 0; attempt < 20; attempt++)
            if (login.begin(identity, IPV4, limits) instanceof Limited) return;
        throw new AssertionError("no delay after 20 attempts");
    }

    @Test
    void noKeyContainsThePlainDigestOfAnIdentityOrOfAnAddress() {
        var keys = keysAfterEveryCounter();

        var plain =
                Stream.of(EMAIL, OTHER_EMAIL, LOGIN, DELAYED, IPV4, IPV6, PREFIX_48, FAMILY)
                        .map(AuthenticationSecrets::hash)
                        .toList();
        assertThat(keys)
                .as("keys of Redis")
                .hasSizeGreaterThan(10)
                .noneMatch(key -> plain.stream().anyMatch(key::contains));
    }

    @Test
    void everyKindOfCounterIsNamedByTheKeyedDigest() {
        var keys = keysAfterEveryCounter();
        var named = TestQuotaKeys.KEYS;

        assertThat(keys)
                .contains(
                        "auth:limits:registration-mail:identity:" + named.identity(EMAIL),
                        "auth:limits:registration-mail:cooldown:" + named.identity(EMAIL),
                        "auth:limits:registration-mail:ip:" + named.address(IPV4),
                        "auth:limits:registration-mail:prefix48:" + named.prefix48(PREFIX_48),
                        "auth:limits:resume:identity:" + named.identity(FAMILY),
                        "auth:limits:ip:" + named.address(IPV4),
                        "auth:limits:login:failures:" + named.identity(LOGIN),
                        "auth:limits:login:delay:" + named.identity(DELAYED));
    }

    @Test
    void countersAreCheckedWithoutReportingALossWhenTheWitnessIsThere() {
        var losses = meters.get("auth.rate_limit.counters.lost").counter();
        double before = losses.count();

        keysAfterEveryCounter();

        assertThat(losses.count() - before).as("losses reported").isZero();
    }

    @Test
    void anotherKeyStartsEveryCounterAtZeroWithoutReportingALoss() {
        var rotated =
                new RedisAuthenticationRateLimiter(
                        template,
                        new QuotaKeys(
                                new MockEnvironment()
                                        .withProperty(
                                                QuotaKeys.PROPERTY,
                                                java.util.Base64.getEncoder()
                                                        .encodeToString(new byte[32]))),
                        Clock.systemUTC(),
                        meters);
        var tight =
                new QuotaLimits(
                        100,
                        1,
                        Duration.ofMinutes(15),
                        new MailLimits(Duration.ofSeconds(60), 1000, 5));
        double before = meters.get("auth.rate_limit.counters.lost").counter().count();
        limiter.check(QuotaScope.REGISTRATION_MAIL, EMAIL, IPV4, tight);
        assertThat(limiter.check(QuotaScope.REGISTRATION_MAIL, EMAIL, IPV4, tight))
                .as("same key, counted twice")
                .isNotInstanceOf(AuthenticationRateLimiter.Allowed.class);

        assertThat(rotated.check(QuotaScope.REGISTRATION_MAIL, EMAIL, IPV4, tight))
                .as("another key")
                .isInstanceOf(AuthenticationRateLimiter.Allowed.class);
        assertThat(meters.get("auth.rate_limit.counters.lost").counter().count() - before)
                .as("losses reported")
                .isZero();
    }
}
