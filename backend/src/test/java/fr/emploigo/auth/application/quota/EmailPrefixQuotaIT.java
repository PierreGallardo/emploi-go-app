package fr.emploigo.auth.application.quota;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import fr.emploigo.auth.application.AuthenticationIntegrationTest;
import fr.emploigo.auth.application.failure.AuthenticationFailedException;

import io.micrometer.core.instrument.MeterRegistry;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.stream.IntStream;

/**
 * The /48 IPv6 prefix limit on email-sending actions, with production values: spreading over
 * networks does not escape it.
 */
class EmailPrefixQuotaIT extends AuthenticationIntegrationTest {
    private static final int PREFIX_LIMIT = 120;

    @Autowired QuotaEnforcer quota;
    @Autowired MeterRegistry meters;

    private static String address(int prefix, int index) {
        return "2001:db8:"
                + Integer.toHexString(prefix)
                + ":"
                + Integer.toHexString(index << 4)
                + "::1";
    }

    private void send(QuotaScope scope, int prefix, int index) {
        quota.check(
                scope,
                "jeune-" + prefix + "-" + index + "@example.invalid",
                address(prefix, index));
    }

    private double refusals(QuotaScope scope) {
        return meters.get("auth.rate_limit.prefix.limited")
                .tag("scope", scope.key())
                .counter()
                .count();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(
            value = QuotaScope.class,
            names = {"REGISTRATION_MAIL", "RECOVERY_MAIL", "GOOGLE_MAIL"})
    void clientThatSpreadsOverTheNetworksOfItsPrefixIsStoppedAtTheLimitOfThePrefix(
            QuotaScope scope) {
        double before = refusals(scope);
        // One attempt per /60: no address counter is anywhere near its own limit.
        IntStream.range(0, PREFIX_LIMIT).forEach(index -> send(scope, 1, index));

        var refusal =
                catchThrowableOfType(
                        AuthenticationFailedException.class, () -> send(scope, 1, PREFIX_LIMIT));

        assertThat(refusal).as("refusal").isNotNull();
        assertThat(refusal.reason())
                .as("reason")
                .isEqualTo(AuthenticationFailedException.Reason.RATE_LIMITED);
        assertThat(refusal.retryAfter()).as("retry after").isPositive();
        assertThat(refusals(scope) - before).as("refusals by prefix").isEqualTo(1);
    }
}
