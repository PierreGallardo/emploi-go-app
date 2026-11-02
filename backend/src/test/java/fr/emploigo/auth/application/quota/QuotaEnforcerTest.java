package fr.emploigo.auth.application.quota;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;

import fr.emploigo.auth.application.failure.AuthenticationFailedException;
import fr.emploigo.auth.application.quota.AuthenticationRateLimiter.Allowed;
import fr.emploigo.auth.application.quota.AuthenticationRateLimiter.Cause;
import fr.emploigo.auth.application.quota.AuthenticationRateLimiter.Limited;
import fr.emploigo.auth.application.quota.AuthenticationRateLimiter.Unavailable;
import fr.emploigo.auth.infrastructure.quota.QuotaTestProperties;
import fr.emploigo.support.LogCapture;
import fr.emploigo.support.TestClock;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Duration;
import java.util.stream.Stream;

class QuotaEnforcerTest {
    private static final String IDENTITY = "account:42";
    private static final String ADDRESS = "203.0.113.7";

    private final AuthenticationRateLimiter limiter = mock(AuthenticationRateLimiter.class);
    private final LoginAttemptLimiter loginLimiter = mock(LoginAttemptLimiter.class);
    private final TestClock clock = new TestClock();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    private QuotaEnforcer quota() {
        return new QuotaEnforcer(
                limiter, loginLimiter, meters, clock, QuotaTestProperties.settings());
    }

    private void unavailable(QuotaScope scope) {
        when(limiter.check(scope, IDENTITY, ADDRESS, QuotaTestProperties.limits(scope)))
                .thenReturn(new Unavailable());
    }

    private double count(QuotaScope scope, String outcome) {
        var counter =
                meters.find("auth.rate_limit.unavailable")
                        .tag("scope", scope.key())
                        .tag("outcome", outcome)
                        .counter();
        return counter == null ? 0 : counter.count();
    }

    static Stream<Arguments> refusalsByCause() {
        return Stream.of(
                argumentSet(
                        "délai entre deux envois",
                        Cause.SEND_DELAY,
                        AuthenticationFailedException.Reason.MAIL_RECENTLY_SENT),
                argumentSet(
                        "limite de volume",
                        Cause.VOLUME,
                        AuthenticationFailedException.Reason.RATE_LIMITED));
    }

    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("refusalsByCause")
    void limitReachedFailsWithTheReasonOfItsCauseAndTheWaitOfTheCounter(
            Cause cause, AuthenticationFailedException.Reason reason) {
        when(limiter.check(
                        QuotaScope.REGISTRATION_MAIL,
                        IDENTITY,
                        ADDRESS,
                        QuotaTestProperties.limits(QuotaScope.REGISTRATION_MAIL)))
                .thenReturn(new Limited(Duration.ofSeconds(30), cause));

        var failure =
                catchThrowableOfType(
                        AuthenticationFailedException.class,
                        () ->
                                quota()
                                        .check(QuotaScope.REGISTRATION_MAIL, IDENTITY, ADDRESS));

        assertThat(failure).as("failure").isNotNull();
        assertThat(failure.reason()).as("reason").isEqualTo(reason);
        assertThat(failure.retryAfter()).as("retry after").isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void limitReachedRefusesAnOpenActionWithTheWaitOfTheCounter() {
        when(limiter.check(
                        QuotaScope.GOOGLE_START,
                        IDENTITY,
                        ADDRESS,
                        QuotaTestProperties.limits(QuotaScope.GOOGLE_START)))
                .thenReturn(new Limited(Duration.ofSeconds(42), Cause.VOLUME));

        var failure =
                catchThrowableOfType(
                        AuthenticationFailedException.class,
                        () ->
                                quota()
                                        .check(QuotaScope.GOOGLE_START, IDENTITY, ADDRESS));

        assertThat(failure).as("failure").isNotNull();
        assertThat(failure.reason())
                .as("reason")
                .isEqualTo(AuthenticationFailedException.Reason.RATE_LIMITED);
        assertThat(failure.retryAfter()).as("retry after").isEqualTo(Duration.ofSeconds(42));
    }

    @Test
    void openScopeWritesAWarningWithoutIdentityOrAddress() {
        unavailable(QuotaScope.GOOGLE_START);

        try (var logs = LogCapture.of(QuotaEnforcer.class)) {
            quota().check(QuotaScope.GOOGLE_START, IDENTITY, ADDRESS);

            assertThat(logs.events())
                    .as("events")
                    .singleElement()
                    .satisfies(
                            event -> {
                                assertThat(event.getLevel()).as("level").isEqualTo(Level.WARN);
                                assertThat(event.getFormattedMessage())
                                        .as("message")
                                        .contains("google-start")
                                        .doesNotContain(IDENTITY)
                                        .doesNotContain(ADDRESS);
                            });
        }
    }

    @Test
    void warningIsWrittenOncePerScopeAndPerMinuteWhileEveryExecutionIsCounted() {
        when(limiter.check(
                        QuotaScope.CONFIRMATION,
                        IDENTITY,
                        ADDRESS,
                        QuotaTestProperties.limits(QuotaScope.CONFIRMATION)))
                .thenReturn(new Unavailable());
        var quota = quota();

        try (var logs = LogCapture.of(QuotaEnforcer.class)) {
            quota.check(QuotaScope.CONFIRMATION, IDENTITY, ADDRESS);
            clock.now = clock.now.plusSeconds(59);
            quota.check(QuotaScope.CONFIRMATION, IDENTITY, ADDRESS);
            assertThat(logs.events()).as("warnings within the minute").hasSize(1);

            clock.now = clock.now.plusSeconds(1);
            quota.check(QuotaScope.CONFIRMATION, IDENTITY, ADDRESS);
            assertThat(logs.events()).as("warnings after a minute").hasSize(2);
        }
        assertThat(count(QuotaScope.CONFIRMATION, "executed")).as("executions").isEqualTo(3);
    }

    @Test
    void anotherScopeWritesItsOwnWarningWithinTheSameMinute() {
        when(limiter.check(
                        QuotaScope.CONFIRMATION,
                        IDENTITY,
                        ADDRESS,
                        QuotaTestProperties.limits(QuotaScope.CONFIRMATION)))
                .thenReturn(new Unavailable());
        unavailable(QuotaScope.GOOGLE_START);
        var quota = quota();

        try (var logs = LogCapture.of(QuotaEnforcer.class)) {
            quota.check(QuotaScope.CONFIRMATION, IDENTITY, ADDRESS);
            quota.check(QuotaScope.GOOGLE_START, IDENTITY, ADDRESS);

            assertThat(logs.events()).as("warnings").hasSize(2);
        }
    }

    @Test
    void identityOnlyActionIsCountedWithTheLimitsOfItsIdentityAlone() {
        when(limiter.checkIdentity(
                        QuotaScope.RESUME,
                        IDENTITY,
                        QuotaTestProperties.identityLimits(QuotaScope.RESUME)))
                .thenReturn(new Allowed());

        assertThatCode(() -> quota().checkIdentity(QuotaScope.RESUME, IDENTITY))
                .as("check")
                .doesNotThrowAnyException();

        verify(limiter, never()).check(any(), any(), any(), any());
    }

    @Test
    void identityOnlyActionAtItsLimitFailsWithTheWaitOfTheCounter() {
        when(limiter.checkIdentity(
                        QuotaScope.RESUME,
                        IDENTITY,
                        QuotaTestProperties.identityLimits(QuotaScope.RESUME)))
                .thenReturn(new Limited(Duration.ofSeconds(37), Cause.VOLUME));

        var failure =
                catchThrowableOfType(
                        AuthenticationFailedException.class,
                        () -> quota().checkIdentity(QuotaScope.RESUME, IDENTITY));

        assertThat(failure).as("failure").isNotNull();
        assertThat(failure.reason())
                .as("reason")
                .isEqualTo(AuthenticationFailedException.Reason.RATE_LIMITED);
        assertThat(failure.retryAfter()).as("retry after").isEqualTo(Duration.ofSeconds(37));
    }

    @Test
    void openIdentityOnlyActionRunsWithAWarningWhenCountersAreDown() {
        when(limiter.checkIdentity(
                        QuotaScope.RESUME,
                        IDENTITY,
                        QuotaTestProperties.identityLimits(QuotaScope.RESUME)))
                .thenReturn(new Unavailable());

        try (var logs = LogCapture.of(QuotaEnforcer.class)) {
            assertThatCode(() -> quota().checkIdentity(QuotaScope.RESUME, IDENTITY))
                    .as("check")
                    .doesNotThrowAnyException();

            assertThat(logs.events())
                    .as("events")
                    .singleElement()
                    .satisfies(
                            event -> {
                                assertThat(event.getLevel()).as("level").isEqualTo(Level.WARN);
                                assertThat(event.getFormattedMessage())
                                        .as("message")
                                        .contains("resume")
                                        .doesNotContain(IDENTITY);
                            });
        }
        assertThat(count(QuotaScope.RESUME, "executed")).as("executions").isEqualTo(1);
    }

    @Test
    void closedScopeWritesNoWarning() {
        unavailable(QuotaScope.RECOVERY_CONFIRM);

        try (var logs = LogCapture.of(QuotaEnforcer.class)) {
            catchThrowableOfType(
                    AuthenticationFailedException.class,
                    () ->
                            quota()
                                    .check(QuotaScope.RECOVERY_CONFIRM, IDENTITY, ADDRESS));

            assertThat(logs.events()).as("events").isEmpty();
        }
    }

    @Test
    void everyScopeAndOutcomeIsVisibleAtZeroBeforeAnyOutage() {
        quota();

        assertThat(meters.find("auth.rate_limit.unavailable").counters())
                .as("counters")
                .hasSize(QuotaScope.values().length * 2);
    }

    static Stream<Arguments> clientNetworks() {
        return Stream.of(
                argumentSet(
                        "IPv4 mappée en IPv6",
                        QuotaScope.RECOVERY_CONFIRM,
                        "::ffff:203.0.113.7",
                        "203.0.113.7"),
                argumentSet(
                        "IPv6 ramenée à son réseau /60",
                        QuotaScope.RECOVERY_CONFIRM,
                        "2001:db8:1:2:3:4:5:6",
                        "v6:20010db80001000"),
                argumentSet(
                        "IPv6 ramenée à son réseau /60 pour l'inscription",
                        QuotaScope.REGISTRATION_MAIL,
                        "2001:db8:1:2:3:4:5:6",
                        "v6:20010db80001000"),
                argumentSet(
                        "IPv6 ramenée à son réseau /60 pour la confirmation",
                        QuotaScope.CONFIRMATION,
                        "2001:db8:1:2:3:4:5:6",
                        "v6:20010db80001000"));
    }

    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("clientNetworks")
    void addressIsReducedToItsClientNetworkBeforeItIsCounted(
            QuotaScope scope, String address, String network) {
        when(limiter.check(scope, IDENTITY, network, QuotaTestProperties.limits(scope)))
                .thenReturn(new Allowed());

        assertThatCode(() -> quota().check(scope, IDENTITY, address))
                .as("check")
                .doesNotThrowAnyException();
    }

    @Test
    void addressOnlyActionCountsTheClientNetworkAsItsIdentityToo() {
        var mapped = "::ffff:203.0.113.7";
        when(limiter.check(
                        QuotaScope.CONFIRMATION,
                        "203.0.113.7",
                        "203.0.113.7",
                        QuotaTestProperties.limits(QuotaScope.CONFIRMATION)))
                .thenReturn(new Allowed());

        assertThatCode(() -> quota().checkAddress(QuotaScope.CONFIRMATION, mapped))
                .as("check")
                .doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(
            value = QuotaScope.class,
            mode = EnumSource.Mode.EXCLUDE,
            names = {"LOGIN", "RESUME"})
    void everyScopeIsCountedWithTheLimitsOfItsConfiguration(QuotaScope scope) {
        when(limiter.check(scope, IDENTITY, ADDRESS, QuotaTestProperties.limits(scope)))
                .thenReturn(new Allowed());

        assertThatCode(() -> quota().check(scope, IDENTITY, ADDRESS))
                .as("check")
                .doesNotThrowAnyException();
    }

    @Test
    void unreadableAddressIsCountedAsItIsAndReportedWithoutItsValue() {
        var unreadable = "198.51.100.7:54321";
        when(limiter.check(
                        QuotaScope.RECOVERY_CONFIRM,
                        IDENTITY,
                        unreadable,
                        QuotaTestProperties.limits(QuotaScope.RECOVERY_CONFIRM)))
                .thenReturn(new Allowed());

        try (var logs = LogCapture.of(QuotaEnforcer.class)) {
            quota().check(QuotaScope.RECOVERY_CONFIRM, IDENTITY, unreadable);

            verify(limiter)
                    .check(
                            QuotaScope.RECOVERY_CONFIRM,
                            IDENTITY,
                            unreadable,
                            QuotaTestProperties.limits(QuotaScope.RECOVERY_CONFIRM));
            assertThat(logs.events())
                    .as("events")
                    .singleElement()
                    .satisfies(
                            event -> {
                                assertThat(event.getLevel()).as("level").isEqualTo(Level.WARN);
                                assertThat(event.getFormattedMessage())
                                        .as("message")
                                        .doesNotContain("198.51.100");
                            });
        }
        assertThat(meters.get("auth.rate_limit.address.unreadable").counter().count())
                .as("unreadable")
                .isEqualTo(1);
    }

    @Test
    void unreadableAddressWritesOneWarningPerMinuteWhileEveryOneIsCounted() {
        var unreadable = "not-an-address";
        when(limiter.check(
                        QuotaScope.RECOVERY_CONFIRM,
                        IDENTITY,
                        unreadable,
                        QuotaTestProperties.limits(QuotaScope.RECOVERY_CONFIRM)))
                .thenReturn(new Allowed());
        var quota = quota();

        try (var logs = LogCapture.of(QuotaEnforcer.class)) {
            quota.check(QuotaScope.RECOVERY_CONFIRM, IDENTITY, unreadable);
            clock.now = clock.now.plusSeconds(59);
            quota.check(QuotaScope.RECOVERY_CONFIRM, IDENTITY, unreadable);
            assertThat(logs.events()).as("warnings within the minute").hasSize(1);

            clock.now = clock.now.plusSeconds(1);
            quota.check(QuotaScope.RECOVERY_CONFIRM, IDENTITY, unreadable);
            assertThat(logs.events()).as("warnings after a minute").hasSize(2);
        }
        assertThat(meters.get("auth.rate_limit.address.unreadable").counter().count())
                .as("unreadable")
                .isEqualTo(3);
    }

    @Test
    void readableAddressIsNeverReportedAsUnreadable() {
        when(limiter.check(
                        QuotaScope.RECOVERY_CONFIRM,
                        IDENTITY,
                        ADDRESS,
                        QuotaTestProperties.limits(QuotaScope.RECOVERY_CONFIRM)))
                .thenReturn(new Allowed());

        quota().check(QuotaScope.RECOVERY_CONFIRM, IDENTITY, ADDRESS);

        assertThat(meters.get("auth.rate_limit.address.unreadable").counter().count())
                .as("unreadable")
                .isZero();
    }

    private double unrecorded(String outcome) {
        return meters.get("auth.rate_limit.login.unrecorded")
                .tag("outcome", outcome)
                .counter()
                .count();
    }

    private void admitted() {
        when(loginLimiter.begin("jeune", ADDRESS, QuotaTestProperties.loginLimits()))
                .thenReturn(new LoginAttemptLimiter.Admitted());
    }

    static Stream<Arguments> loginNetworks() {
        return Stream.of(
                argumentSet("IPv4 mappée en IPv6", "::ffff:203.0.113.7", "203.0.113.7"),
                argumentSet(
                        "IPv6 ramenée à son réseau /60",
                        "2001:db8:1:2:3:4:5:6",
                        "v6:20010db80001000"));
    }

    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("loginNetworks")
    void loginIsBegunWithItsOwnLimitsAndTheClientNetwork(String address, String network) {
        when(loginLimiter.begin("jeune", network, QuotaTestProperties.loginLimits()))
                .thenReturn(new LoginAttemptLimiter.Admitted());

        assertThatCode(() -> quota().beginLogin("jeune", address))
                .as("begin")
                .doesNotThrowAnyException();
    }

    @Test
    void loginDelayFailsWithTheRemainingWait() {
        when(loginLimiter.begin("jeune", ADDRESS, QuotaTestProperties.loginLimits()))
                .thenReturn(new LoginAttemptLimiter.Limited(Duration.ofSeconds(17)));

        var failure =
                catchThrowableOfType(
                        AuthenticationFailedException.class,
                        () -> quota().beginLogin("jeune", ADDRESS));

        assertThat(failure.reason())
                .as("reason")
                .isEqualTo(AuthenticationFailedException.Reason.RATE_LIMITED);
        assertThat(failure.retryAfter()).as("retry after").isEqualTo(Duration.ofSeconds(17));
    }

    @Test
    void loginIsRefusedWhenTheCountersAreUnavailableAndTheRefusalIsCounted() {
        when(loginLimiter.begin("jeune", ADDRESS, QuotaTestProperties.loginLimits()))
                .thenReturn(new LoginAttemptLimiter.Unavailable());

        var failure =
                catchThrowableOfType(
                        AuthenticationFailedException.class,
                        () -> quota().beginLogin("jeune", ADDRESS));

        assertThat(failure.reason())
                .as("reason")
                .isEqualTo(AuthenticationFailedException.Reason.UNAVAILABLE);
        assertThat(count(QuotaScope.LOGIN, "refused")).as("refused").isEqualTo(1);
    }

    @Test
    void successForgetsTheIdentityOnceWhateverTheNumberOfCalls() {
        admitted();
        when(loginLimiter.forget("jeune")).thenReturn(true);
        var attempt = quota().beginLogin("jeune", ADDRESS);

        attempt.succeed();
        attempt.succeed();

        verify(loginLimiter, times(1)).forget("jeune");
    }

    @Test
    void successThatCouldNotBeRecordedIsCountedAndWarnedOncePerMinute() {
        admitted();
        when(loginLimiter.forget("jeune")).thenReturn(false);
        var check = quota();

        try (var logs = LogCapture.of(QuotaEnforcer.class)) {
            assertThatCode(() -> check.beginLogin("jeune", ADDRESS).succeed())
                    .as("first")
                    .doesNotThrowAnyException();
            clock.now = clock.now.plusSeconds(30);
            assertThatCode(() -> check.beginLogin("jeune", ADDRESS).succeed())
                    .as("second")
                    .doesNotThrowAnyException();

            assertThat(logs.events())
                    .as("warnings within the minute")
                    .singleElement()
                    .satisfies(
                            event -> {
                                assertThat(event.getLevel()).as("level").isEqualTo(Level.WARN);
                                assertThat(event.getFormattedMessage())
                                        .as("message")
                                        .doesNotContain("jeune")
                                        .doesNotContain(ADDRESS);
                            });
        }
        assertThat(unrecorded("succeeded")).as("unrecorded successes").isEqualTo(2);
    }

    @Test
    void recordingThatThrowsNeverReachesTheCallerAndIsCountedAsUnrecorded() {
        admitted();
        when(loginLimiter.forget("jeune"))
                .thenThrow(new IllegalStateException("adaptateur défaillant"));
        var attempt = quota().beginLogin("jeune", ADDRESS);

        assertThatCode(attempt::succeed).as("success").doesNotThrowAnyException();

        assertThat(unrecorded("succeeded")).as("unrecorded successes").isEqualTo(1);
    }

    private static final String EMAIL_KEY = "jeune@example.invalid";
    private static final String USERNAME = "jeune";

    @Test
    void resetLoginForgetsTheEmailKeyAndTheUsername() {
        when(loginLimiter.forget(EMAIL_KEY)).thenReturn(true);
        when(loginLimiter.forget(USERNAME)).thenReturn(true);

        quota().resetLogin(EMAIL_KEY, USERNAME);

        verify(loginLimiter).forget(EMAIL_KEY);
        verify(loginLimiter).forget(USERNAME);
        assertThat(unrecorded("reset")).as("unrecorded resets").isZero();
    }

    @Test
    void unrecordedLoginResetIsCountedPerIdentityAndWarnedOnce() {
        when(loginLimiter.forget(EMAIL_KEY)).thenReturn(false);
        when(loginLimiter.forget(USERNAME)).thenReturn(false);
        var check = quota();

        try (var logs = LogCapture.of(QuotaEnforcer.class)) {
            assertThatCode(() -> check.resetLogin(EMAIL_KEY, USERNAME))
                    .as("reset")
                    .doesNotThrowAnyException();

            assertThat(logs.events())
                    .as("warnings")
                    .singleElement()
                    .satisfies(
                            event -> {
                                assertThat(event.getLevel()).as("level").isEqualTo(Level.WARN);
                                assertThat(event.getFormattedMessage())
                                        .as("message")
                                        .contains("reset")
                                        .doesNotContain(EMAIL_KEY)
                                        .doesNotContain(USERNAME);
                            });
        }
        assertThat(unrecorded("reset")).as("unrecorded resets").isEqualTo(2);
        assertThat(unrecorded("succeeded")).as("unrecorded successes").isZero();
    }

    @Test
    void resetLoginStillResetsTheUsernameWhenTheEmailKeyThrows() {
        when(loginLimiter.forget(EMAIL_KEY))
                .thenThrow(new IllegalStateException("adaptateur défaillant"));
        when(loginLimiter.forget(USERNAME)).thenReturn(true);

        assertThatCode(() -> quota().resetLogin(EMAIL_KEY, USERNAME))
                .as("reset")
                .doesNotThrowAnyException();

        verify(loginLimiter).forget(USERNAME);
        assertThat(unrecorded("reset")).as("unrecorded resets").isEqualTo(1);
    }

    @Test
    void resetLoginWarnsAgainAfterAMinute() {
        when(loginLimiter.forget(anyString())).thenReturn(false);
        var check = quota();

        try (var logs = LogCapture.of(QuotaEnforcer.class)) {
            check.resetLogin(EMAIL_KEY, USERNAME);
            clock.now = clock.now.plusSeconds(61);
            check.resetLogin(EMAIL_KEY, USERNAME);

            assertThat(logs.events()).as("warnings").hasSize(2);
        }
    }

    @Test
    void resetLoginStillResetsTheUsernameWhenTheMetricOfTheEmailKeyThrows() {
        var failing = mock(Counter.class);
        doThrow(new IllegalStateException("registre défaillant")).when(failing).increment();
        var registry =
                new SimpleMeterRegistry() {
                    @Override
                    protected Counter newCounter(Meter.Id id) {
                        return "reset".equals(id.getTag("outcome"))
                                ? failing
                                : super.newCounter(id);
                    }
                };
        when(loginLimiter.forget(EMAIL_KEY)).thenReturn(false);
        when(loginLimiter.forget(USERNAME)).thenReturn(true);
        var check =
                new QuotaEnforcer(
                        limiter,
                        loginLimiter,
                        registry,
                        clock,
                        QuotaTestProperties.settings());

        assertThatCode(() -> check.resetLogin(EMAIL_KEY, USERNAME))
                .as("reset")
                .doesNotThrowAnyException();

        verify(loginLimiter).forget(USERNAME);
    }

    @Test
    void unrecordedCountersAreVisibleAtZeroBeforeTheFirstLoss() {
        quota();

        assertThat(unrecorded("succeeded")).as("resets after a success").isZero();
        assertThat(unrecorded("reset")).as("resets after a recovery").isZero();
    }
}
