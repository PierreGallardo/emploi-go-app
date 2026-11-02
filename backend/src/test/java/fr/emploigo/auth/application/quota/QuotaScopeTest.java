package fr.emploigo.auth.application.quota;

import static java.util.Map.entry;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Map;

class QuotaScopeTest {
    /**
     * The partition of the actions: an action is refused when the counters cannot answer if it
     * checks a password or sends an email, and executed with a warning otherwise.
     */
    private static final Map<QuotaScope, FailureMode> EXPECTED =
            Map.ofEntries(
                    entry(QuotaScope.LOGIN, FailureMode.CLOSED),
                    entry(QuotaScope.REGISTRATION_MAIL, FailureMode.CLOSED),
                    entry(QuotaScope.CONFIRMATION, FailureMode.OPEN),
                    entry(QuotaScope.RECOVERY_MAIL, FailureMode.CLOSED),
                    entry(QuotaScope.RECOVERY_CONFIRM, FailureMode.CLOSED),
                    entry(QuotaScope.GOOGLE_START, FailureMode.OPEN),
                    entry(QuotaScope.GOOGLE_CALLBACK, FailureMode.OPEN),
                    entry(QuotaScope.GOOGLE_COMPLETE, FailureMode.OPEN),
                    entry(QuotaScope.GOOGLE_LINK, FailureMode.CLOSED),
                    entry(QuotaScope.GOOGLE_MAIL, FailureMode.CLOSED),
                    entry(QuotaScope.GOOGLE_EMAIL_CONFIRM, FailureMode.OPEN),
                    entry(QuotaScope.RESUME, FailureMode.OPEN));

    @ParameterizedTest(name = "{0}")
    @EnumSource(QuotaScope.class)
    void failureModeIsThePartitionOfTheAction(QuotaScope scope) {
        assertThat(scope.failureMode()).as("failure mode").isEqualTo(EXPECTED.get(scope));
    }
}
