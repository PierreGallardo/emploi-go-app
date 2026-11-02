package fr.emploigo.auth.application.registration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import fr.emploigo.auth.application.failure.AuthenticationFailedException;
import fr.emploigo.auth.application.mail.AuthenticationMail;
import fr.emploigo.auth.application.mail.AuthenticationMailDispatcher;
import fr.emploigo.auth.application.mail.AuthenticationMailSettings;
import fr.emploigo.auth.application.mail.MailFixtures;
import fr.emploigo.auth.application.quota.QuotaEnforcer;
import fr.emploigo.auth.application.quota.QuotaScope;
import fr.emploigo.auth.domain.MailFlow;

import org.junit.jupiter.api.Test;

import java.time.Duration;

class ResendRegistrationCodeUseCaseTest {
    private static final String KEY = "k".repeat(43);
    private static final String EMAIL = "jeune@example.invalid";
    private static final AuthenticationMail MAIL =
            MailFixtures.mail(MailFlow.CONFIRMATION, "CONFIRMATION");

    private final RegistrationTransactions transactions = mock(RegistrationTransactions.class);
    private final QuotaEnforcer quota = mock(QuotaEnforcer.class);
    private final AuthenticationMailDispatcher dispatcher =
            mock(AuthenticationMailDispatcher.class);
    private final FakeConfirmationAttemptLimiter limiter = new FakeConfirmationAttemptLimiter();
    private final ResendRegistrationCodeUseCase registration =
            new ResendRegistrationCodeUseCase(
                    transactions,
                    quota,
                    new RegistrationGatekeeper(enabledMail(), limiter),
                    new RegistrationCodeMailer(dispatcher),
                    limiter);

    private static AuthenticationMailSettings enabledMail() {
        return new AuthenticationMailSettings(
                true, "confirmation@example.invalid", "https://example.invalid");
    }

    private static RegistrationFailedException.Reason reasonOf(Throwable error) {
        return ((RegistrationFailedException) error).reason();
    }

    @Test
    void disabledMailIsReportedUnavailableBeforeAnyWrite() {
        var mail =
                new AuthenticationMailSettings(
                        false, "confirmation@example.invalid", "https://example.invalid");
        var disabled =
                new ResendRegistrationCodeUseCase(
                        transactions,
                        quota,
                        new RegistrationGatekeeper(mail, limiter),
                        new RegistrationCodeMailer(dispatcher),
                        limiter);

        assertThatThrownBy(() -> disabled.resend(EMAIL, KEY, "127.0.0.1"))
                .as("resend")
                .isInstanceOfSatisfying(
                        RegistrationFailedException.class,
                        failure ->
                                assertThat(failure.reason())
                                        .as("reason")
                                        .isEqualTo(RegistrationFailedException.Reason.UNAVAILABLE));

        verifyNoInteractions(transactions);
    }

    @Test
    void resendInsideTheDelayBetweenTwoMailsIsRefusedBeforeTheTransactionAndTheMail() {
        doThrow(AuthenticationFailedException.mailRecentlySent(Duration.ofSeconds(30)))
                .when(quota)
                .check(QuotaScope.REGISTRATION_MAIL, EMAIL, "127.0.0.1");

        assertThatThrownBy(() -> registration.resend(EMAIL, KEY, "127.0.0.1"))
                .as("resend")
                .isInstanceOfSatisfying(
                        AuthenticationFailedException.class,
                        failure ->
                                assertThat(failure.reason())
                                        .as("reason")
                                        .isEqualTo(
                                                AuthenticationFailedException.Reason
                                                        .MAIL_RECENTLY_SENT));

        verifyNoInteractions(transactions, dispatcher);
        assertThat(limiter.issued()).as("codes issued").isEmpty();
    }

    @Test
    void resendUsesTheQuotaOfTheMailAndSendsTheCodeOfTheTransaction() {
        when(transactions.resend(EMAIL, KEY)).thenReturn(MAIL);
        when(dispatcher.send(MAIL)).thenReturn(true);

        registration.resend(EMAIL, KEY, "127.0.0.1");

        verify(quota).check(QuotaScope.REGISTRATION_MAIL, EMAIL, "127.0.0.1");
        verify(dispatcher).send(MAIL);
    }

    @Test
    void resendTellsTheDelayOfTheMailQuota() {
        when(transactions.resend(EMAIL, KEY)).thenReturn(MAIL);
        when(dispatcher.send(MAIL)).thenReturn(true);
        when(quota.sendDelay(QuotaScope.REGISTRATION_MAIL)).thenReturn(Duration.ofSeconds(60));

        var wait = registration.resend(EMAIL, KEY, "127.0.0.1");

        assertThat(wait).as("wait before another email").isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void resendSendsTheCodeAfterTheTransaction() {
        when(transactions.resend(EMAIL, KEY)).thenReturn(MAIL);
        when(dispatcher.send(MAIL)).thenReturn(true);

        registration.resend(EMAIL, KEY, "127.0.0.1");

        var order = inOrder(transactions, dispatcher);
        order.verify(transactions).resend(EMAIL, KEY);
        order.verify(dispatcher).send(MAIL);
    }

    @Test
    void resendWhoseMailIsNotAcceptedIsUnavailable() {
        when(transactions.resend(any(), any())).thenReturn(MAIL);
        when(dispatcher.send(any())).thenReturn(false);

        assertThatThrownBy(() -> registration.resend(EMAIL, KEY, "127.0.0.1"))
                .as("resend")
                .isInstanceOf(RegistrationFailedException.class)
                .extracting(ResendRegistrationCodeUseCaseTest::reasonOf)
                .isEqualTo(RegistrationFailedException.Reason.UNAVAILABLE);
    }

    @Test
    void resendOfAPageWhoseAttemptsAreBlockedIsRefusedBeforeTheQuotaAndTheTransaction() {
        limiter.blockedFor(Duration.ofMinutes(30));

        assertThatThrownBy(() -> registration.resend(EMAIL, KEY, "127.0.0.1"))
                .as("resend")
                .isInstanceOf(AuthenticationFailedException.class);

        verifyNoInteractions(quota, transactions, dispatcher);
    }

    @Test
    void resendMarksANewCodeIssuedForThePage() {
        when(transactions.resend(any(), any())).thenReturn(MAIL);
        when(dispatcher.send(any())).thenReturn(true);

        registration.resend(EMAIL, KEY, "127.0.0.1");

        assertThat(limiter.issued())
                .as("codes issued")
                .containsExactly(new FakeConfirmationAttemptLimiter.Issue(EMAIL, KEY));
    }
}
