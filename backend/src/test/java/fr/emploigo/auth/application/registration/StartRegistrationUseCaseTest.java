package fr.emploigo.auth.application.registration;

import static org.assertj.core.api.Assertions.assertThat;
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
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import fr.emploigo.auth.application.failure.AuthenticationFailedException;
import fr.emploigo.auth.application.mail.AuthenticationMail;
import fr.emploigo.auth.application.mail.AuthenticationMailSettings;
import fr.emploigo.auth.application.mail.MailFixtures;
import fr.emploigo.auth.application.password.PasswordHasher;
import fr.emploigo.auth.application.password.PasswordHashingBusyException;
import fr.emploigo.auth.application.quota.QuotaEnforcer;
import fr.emploigo.auth.application.quota.QuotaScope;
import fr.emploigo.auth.domain.MailFlow;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

class StartRegistrationUseCaseTest {
    private static final String PASSWORD = "Une-Phrase-2026-fictive!";
    private static final String KEY = "k".repeat(43);
    private static final String EMAIL = "jeune@example.invalid";

    private final RegistrationTransactions transactions = mock(RegistrationTransactions.class);
    private final QuotaEnforcer quota = mock(QuotaEnforcer.class);
    private final PasswordHasher hashing = mock(PasswordHasher.class);
    private final FakeConfirmationAttemptLimiter limiter = new FakeConfirmationAttemptLimiter();
    private final RegistrationCodeMailer mailer = mock(RegistrationCodeMailer.class);
    private final StartRegistrationUseCase registration =
            new StartRegistrationUseCase(
                    transactions,
                    quota,
                    hashing,
                    new RegistrationGatekeeper(enabledMail(), limiter),
                    limiter,
                    mailer);
    private final AuthenticationMail mail =
            MailFixtures.mail(MailFlow.CONFIRMATION, "CONFIRMATION");

    private static AuthenticationMailSettings enabledMail() {
        return new AuthenticationMailSettings(
                true, "confirmation@example.invalid", "https://example.invalid");
    }

    @BeforeEach
    void newRequest() {
        when(transactions.start(any(), any(), any())).thenReturn(mail);
    }

    private void start() {
        registration.start(EMAIL, PASSWORD, PASSWORD, KEY, "127.0.0.1");
    }

    @Test
    void passwordRefusedByTheRuleCostsNoQuotaAndWritesNothing() {
        assertThatThrownBy(
                        () ->
                                registration.start(
                                        EMAIL, "faible", "faible", KEY, "127.0.0.1"))
                .as("start")
                .isInstanceOfSatisfying(
                        RegistrationFailedException.class,
                        failure ->
                                assertThat(failure.reason())
                                        .as("reason")
                                        .isEqualTo(
                                                RegistrationFailedException.Reason.WEAK_PASSWORD));

        verifyNoInteractions(quota, transactions, mailer);
    }

    @Test
    void limitReachedStopsBeforeTheQuotaAndTheWrites() {
        doThrow(new PasswordHashingBusyException()).when(hashing).requireCapacity();

        assertThatThrownBy(this::start).as("start").isInstanceOf(PasswordHashingBusyException.class);

        verifyNoInteractions(quota, transactions);
    }

    @Test
    void limitReachedAtTheComputationWritesNothing() {
        when(hashing.encode(PASSWORD)).thenThrow(new PasswordHashingBusyException());

        assertThatThrownBy(this::start).as("start").isInstanceOf(PasswordHashingBusyException.class);

        verify(transactions, never()).start(any(), any(), any());
    }

    @Test
    void refusedQuotaReachesNeitherTheComputationNorTheWrites() {
        doThrow(AuthenticationFailedException.rateLimited(Duration.ofMinutes(1)))
                .when(quota)
                .check(eq(QuotaScope.REGISTRATION_MAIL), anyString(), anyString());

        assertThatThrownBy(this::start).as("start").isInstanceOf(AuthenticationFailedException.class);

        verify(hashing, never()).encode(anyString());
        verifyNoInteractions(transactions, mailer);
    }

    @Test
    void signupSendsTheCodeOfTheTransaction() {
        start();

        var order = inOrder(quota, mailer);
        order.verify(quota).check(QuotaScope.REGISTRATION_MAIL, EMAIL, "127.0.0.1");
        order.verify(mailer).send(mail);
        order.verify(quota).sendDelay(QuotaScope.REGISTRATION_MAIL);
        verifyNoMoreInteractions(quota);
    }

    @Test
    void signupTellsTheDelayOfTheMailQuota() {
        when(quota.sendDelay(QuotaScope.REGISTRATION_MAIL)).thenReturn(Duration.ofSeconds(60));

        var wait = registration.start(EMAIL, PASSWORD, PASSWORD, KEY, "127.0.0.1");

        assertThat(wait).as("wait before another email").isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void signupSendsTheCodeAfterTheTransaction() {
        start();

        var order = inOrder(transactions, mailer);
        order.verify(transactions).start(any(), any(), any());
        order.verify(mailer).send(mail);
    }

    @Test
    void signupInsideTheDelayBetweenTwoMailsIsRefusedBeforeTheTransactionAndTheMail() {
        doThrow(AuthenticationFailedException.mailRecentlySent(Duration.ofSeconds(30)))
                .when(quota)
                .check(eq(QuotaScope.REGISTRATION_MAIL), anyString(), anyString());

        assertThatThrownBy(this::start)
                .as("start")
                .isInstanceOfSatisfying(
                        AuthenticationFailedException.class,
                        failure ->
                                assertThat(failure.reason())
                                        .as("reason")
                                        .isEqualTo(
                                                AuthenticationFailedException.Reason
                                                        .MAIL_RECENTLY_SENT));

        verifyNoInteractions(transactions, mailer);
        assertThat(limiter.issued()).as("codes issued").isEmpty();
    }

    @Test
    void signupSendsTheKeyOfThePageToTheTransaction() {
        start();

        verify(transactions).start(eq(EMAIL), any(), eq(KEY));
    }

    @Test
    void signupOnTheEmailOfAnAccountMakesThePageHoldTheEmail() {
        when(transactions.start(any(), any(), any()))
                .thenReturn(MailFixtures.mail(MailFlow.CONFIRMATION, "ACCOUNT_PASSWORD"));

        start();

        assertThat(limiter.takenOver())
                .as("pages that took the email over")
                .containsExactly(new FakeConfirmationAttemptLimiter.Issue(EMAIL, KEY));
    }

    @Test
    void signupThatKeepsARegistrationLeavesThePagesToTheDatabase() {
        start();

        assertThat(limiter.takenOver()).as("pages that took the email over").isEmpty();
    }

    @Test
    void signupMarksANewCodeIssuedForThePage() {
        start();

        assertThat(limiter.issued())
                .as("codes issued")
                .containsExactly(new FakeConfirmationAttemptLimiter.Issue(EMAIL, KEY));
    }
}
