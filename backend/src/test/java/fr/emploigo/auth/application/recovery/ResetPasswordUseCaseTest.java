package fr.emploigo.auth.application.recovery;

import static fr.emploigo.auth.application.recovery.RecoveryFixtures.CHANGED;
import static fr.emploigo.auth.application.recovery.RecoveryFixtures.CHANGED_MAIL;
import static fr.emploigo.auth.application.recovery.RecoveryFixtures.LINK;
import static fr.emploigo.auth.application.recovery.RecoveryFixtures.PASSWORD;
import static fr.emploigo.auth.application.recovery.RecoveryFixtures.disabledMail;
import static fr.emploigo.auth.application.recovery.RecoveryFixtures.enabledMail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import fr.emploigo.auth.application.failure.AuthenticationFailedException;
import fr.emploigo.auth.application.mail.AuthenticationMailDispatcher;
import fr.emploigo.auth.application.password.PasswordHasher;
import fr.emploigo.auth.application.password.PasswordHashingBusyException;
import fr.emploigo.auth.application.quota.QuotaEnforcer;
import fr.emploigo.auth.application.quota.QuotaScope;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Duration;

/**
 * The order of the reset: quota, mail, form of the password, link, computation, write, reset of
 * the login, notice.
 */
class ResetPasswordUseCaseTest {
    private final RecoveryTransactions transactions = mock(RecoveryTransactions.class);
    private final QuotaEnforcer quota = mock(QuotaEnforcer.class);
    private final PasswordHasher hashing = mock(PasswordHasher.class);
    private final AuthenticationMailDispatcher dispatcher =
            mock(AuthenticationMailDispatcher.class);
    private final ResetPasswordUseCase resetPassword =
            new ResetPasswordUseCase(
                    transactions,
                    quota,
                    hashing,
                    new RecoveryGatekeeper(enabledMail()),
                    dispatcher);

    private void reset() {
        resetPassword.reset(LINK, PASSWORD, PASSWORD, "127.0.0.1");
    }

    @Test
    void refusedQuotaReachesNeitherTheLinkNorTheComputation() {
        doThrow(AuthenticationFailedException.rateLimited(Duration.ofMinutes(1)))
                .when(quota)
                .checkAddress(eq(QuotaScope.RECOVERY_CONFIRM), anyString());

        assertThatThrownBy(this::reset)
                .as("reset")
                .isInstanceOf(AuthenticationFailedException.class);

        verifyNoInteractions(transactions, hashing);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(
            value = PasswordRecoveryFailedException.Reason.class,
            names = {"INVALID_LINK", "EXPIRED_LINK", "REPLACED_LINK", "USED_LINK"})
    void unusableLinkStopsTheResetBeforeCostlyWorkAndLeavesTheLoginAlone(
            PasswordRecoveryFailedException.Reason reason) {
        doThrow(new PasswordRecoveryFailedException(reason)).when(transactions).checkLink(any());

        assertThatThrownBy(this::reset)
                .as("reset")
                .isInstanceOf(PasswordRecoveryFailedException.class);

        verifyNoInteractions(hashing);
        verify(quota, never()).resetLogin(any(), any());
    }

    @Test
    void malformedLinkIsRefusedBeforeTheTransactionAndTheComputation() {
        assertThatThrownBy(() -> resetPassword.reset("malformed", PASSWORD, PASSWORD, "127.0.0.1"))
                .as("reset")
                .isInstanceOfSatisfying(
                        PasswordRecoveryFailedException.class,
                        failure ->
                                assertThat(failure.reason())
                                        .as("reason")
                                        .isEqualTo(
                                                PasswordRecoveryFailedException.Reason
                                                        .INVALID_LINK));

        verifyNoInteractions(transactions, hashing);
    }

    @ParameterizedTest(name = "{2}")
    @CsvSource({
        "faible, faible, WEAK_PASSWORD",
        "Une-Phrase-2026-fictive!, Une-Phrase-2026-fictive!x, INVALID_INPUT"
    })
    void refusedPasswordFormIsReportedBeforeAMalformedLink(
            String password, String confirmation, PasswordRecoveryFailedException.Reason reason) {
        assertThatThrownBy(
                        () -> resetPassword.reset("malformed", password, confirmation, "127.0.0.1"))
                .as("reset")
                .isInstanceOfSatisfying(
                        PasswordRecoveryFailedException.class,
                        failure -> assertThat(failure.reason()).as("reason").isEqualTo(reason));

        verifyNoInteractions(transactions);
    }

    @Test
    void unavailableMailIsReportedBeforeAMalformedLink() {
        var disabled =
                new ResetPasswordUseCase(
                        transactions,
                        quota,
                        hashing,
                        new RecoveryGatekeeper(disabledMail()),
                        dispatcher);

        assertThatThrownBy(() -> disabled.reset("malformed", PASSWORD, PASSWORD, "127.0.0.1"))
                .as("reset")
                .isInstanceOfSatisfying(
                        PasswordRecoveryFailedException.class,
                        failure ->
                                assertThat(failure.reason())
                                        .as("reason")
                                        .isEqualTo(
                                                PasswordRecoveryFailedException.Reason
                                                        .UNAVAILABLE));

        verifyNoInteractions(transactions);
    }

    @Test
    void refusedQuotaIsReportedBeforeAMalformedLink() {
        doThrow(AuthenticationFailedException.rateLimited(Duration.ofMinutes(1)))
                .when(quota)
                .checkAddress(eq(QuotaScope.RECOVERY_CONFIRM), anyString());

        assertThatThrownBy(() -> resetPassword.reset("malformed", PASSWORD, PASSWORD, "127.0.0.1"))
                .as("reset")
                .isInstanceOf(AuthenticationFailedException.class);

        verifyNoInteractions(transactions);
    }

    @Test
    void limitReachedAtTheComputationLeavesTheLinkUnconsumed() {
        when(hashing.encode(PASSWORD)).thenThrow(new PasswordHashingBusyException());

        assertThatThrownBy(this::reset)
                .as("reset")
                .isInstanceOf(PasswordHashingBusyException.class);

        verify(transactions, never()).complete(any(), any());
    }

    @Test
    void usedLinkIsRefusedBeforeAnyHashingAndAnyWrite() {
        doThrow(
                        new PasswordRecoveryFailedException(
                                PasswordRecoveryFailedException.Reason.USED_LINK))
                .when(transactions)
                .checkLink(any());

        assertThatThrownBy(this::reset)
                .as("reset")
                .isInstanceOfSatisfying(
                        PasswordRecoveryFailedException.class,
                        failure ->
                                assertThat(failure.reason())
                                        .as("reason")
                                        .isEqualTo(
                                                PasswordRecoveryFailedException.Reason.USED_LINK));

        verifyNoInteractions(hashing, dispatcher);
        verify(transactions, never()).complete(any(), any());
    }

    @Test
    void changedPasswordResetsTheLoginAfterTheTransactionThenSendsTheNoticeOnce() {
        when(transactions.complete(any(), any())).thenReturn(CHANGED);

        reset();

        var order = inOrder(transactions, quota, dispatcher);
        order.verify(transactions).complete(any(), any());
        order.verify(quota).resetLogin("jeune@example.invalid", "jeune");
        order.verify(dispatcher).send(CHANGED_MAIL);
        order.verifyNoMoreInteractions();
    }

    @Test
    void failedTransactionResetsNothingAndSendsNothing() {
        when(transactions.complete(any(), any()))
                .thenThrow(
                        new PasswordRecoveryFailedException(
                                PasswordRecoveryFailedException.Reason.REPLACED_LINK));

        assertThatThrownBy(this::reset)
                .as("reset")
                .isInstanceOf(PasswordRecoveryFailedException.class);

        verify(quota, never()).resetLogin(any(), any());
        verifyNoInteractions(dispatcher);
    }

    @Test
    void changedPasswordIsStillAnsweredWhenTheProviderDoesNotAcceptTheNotification() {
        when(transactions.complete(any(), any())).thenReturn(CHANGED);
        when(dispatcher.send(CHANGED_MAIL)).thenReturn(false);

        assertThatCode(this::reset).as("reset").doesNotThrowAnyException();
    }

    @Test
    void changeKeepsItsIdentitiesOutOfItsStringForm() {
        assertThat(CHANGED.toString()).as("string form").doesNotContain("jeune");
    }
}
