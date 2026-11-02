package fr.emploigo.auth.application.recovery;

import static fr.emploigo.auth.application.recovery.RecoveryFixtures.RESET_MAIL;
import static fr.emploigo.auth.application.recovery.RecoveryFixtures.enabledMail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import fr.emploigo.auth.application.mail.AuthenticationMailDispatcher;
import fr.emploigo.auth.application.quota.QuotaEnforcer;
import fr.emploigo.auth.application.quota.QuotaScope;

import org.junit.jupiter.api.Test;

import java.time.Duration;

class RequestPasswordResetUseCaseTest {
    private final RecoveryTransactions transactions = mock(RecoveryTransactions.class);
    private final QuotaEnforcer quota = mock(QuotaEnforcer.class);
    private final AuthenticationMailDispatcher dispatcher =
            mock(AuthenticationMailDispatcher.class);
    private final RequestPasswordResetUseCase requestReset =
            new RequestPasswordResetUseCase(
                    transactions, quota, new RecoveryGatekeeper(enabledMail()), dispatcher);

    @Test
    void requestSendsTheMailDecidedByTheTransaction() {
        when(transactions.request("jeune@example.invalid")).thenReturn(RESET_MAIL);
        when(dispatcher.send(RESET_MAIL)).thenReturn(true);

        requestReset.request("jeune@example.invalid", "127.0.0.1");

        verify(quota).check(QuotaScope.RECOVERY_MAIL, "jeune@example.invalid", "127.0.0.1");
        verify(dispatcher).send(RESET_MAIL);
    }

    @Test
    void requestSendsTheMailAfterTheTransaction() {
        when(transactions.request("jeune@example.invalid")).thenReturn(RESET_MAIL);
        when(dispatcher.send(RESET_MAIL)).thenReturn(true);

        requestReset.request("jeune@example.invalid", "127.0.0.1");

        var order = inOrder(transactions, dispatcher);
        order.verify(transactions).request("jeune@example.invalid");
        order.verify(dispatcher).send(RESET_MAIL);
    }

    @Test
    void requestTellsTheDelayOfTheMailQuota() {
        when(transactions.request("jeune@example.invalid")).thenReturn(RESET_MAIL);
        when(dispatcher.send(RESET_MAIL)).thenReturn(true);
        when(quota.sendDelay(QuotaScope.RECOVERY_MAIL)).thenReturn(Duration.ofSeconds(60));

        var wait = requestReset.request("jeune@example.invalid", "127.0.0.1");

        assertThat(wait).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void requestIsAnsweredUnavailableWhenTheProviderDoesNotAcceptTheEmail() {
        when(transactions.request("jeune@example.invalid")).thenReturn(RESET_MAIL);
        when(dispatcher.send(RESET_MAIL)).thenReturn(false);

        assertThatThrownBy(() -> requestReset.request("jeune@example.invalid", "127.0.0.1"))
                .as("request")
                .isInstanceOfSatisfying(
                        PasswordRecoveryFailedException.class,
                        error ->
                                assertThat(error.reason())
                                        .as("reason")
                                        .isEqualTo(
                                                PasswordRecoveryFailedException.Reason
                                                        .UNAVAILABLE));
    }
}
