package fr.emploigo.auth.application.registration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import fr.emploigo.auth.application.account.FakeAccountRepository;
import fr.emploigo.auth.application.confirmation.ConfirmationKeySettings;
import fr.emploigo.auth.application.confirmation.ConfirmationTokens;
import fr.emploigo.auth.application.google.FakeGoogleRepository;
import fr.emploigo.auth.application.mail.AuthenticationMail;
import fr.emploigo.auth.application.mail.AuthenticationMailSettings;
import fr.emploigo.auth.application.session.AuthenticationSessionsFixture;
import fr.emploigo.auth.application.session.FakeReconnectionRepository;
import fr.emploigo.auth.application.session.SessionPersistence;
import fr.emploigo.auth.domain.Account;
import fr.emploigo.auth.domain.AuthenticationSecrets;
import fr.emploigo.auth.domain.IdentifierTakenException.Identifier;
import fr.emploigo.auth.domain.MailFlow;
import fr.emploigo.auth.domain.RegistrationCode;
import fr.emploigo.auth.domain.RegistrationPage;
import fr.emploigo.support.TestClock;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

class RegistrationTransactionsTest {
    private static final Instant NOW = Instant.parse("2026-10-17T10:00:00Z");
    private static final String EMAIL = "jeune@example.invalid";
    private static final String KEY = "k".repeat(43);

    private final TestClock clock = new TestClock();
    private final FakeAccountRepository accounts = new FakeAccountRepository();
    private final FakeRegistrationRepository registrations = new FakeRegistrationRepository();
    private final FakeGoogleRepository google = new FakeGoogleRepository();
    private final ConfirmationTokens tokens =
            new ConfirmationTokens(
                    new ConfirmationKeySettings(
                            true, "cle-de-test-des-codes".getBytes(StandardCharsets.UTF_8)));
    private final RegistrationTransactions transactions;

    RegistrationTransactionsTest() {
        clock.now = NOW;
        var reconnections = new FakeReconnectionRepository();
        var sessions =
                AuthenticationSessionsFixture.of(accounts, reconnections, clock, google);
        transactions =
                new RegistrationTransactions(
                        accounts,
                        registrations,
                        google,
                        new AuthenticationMailSettings(
                                true, "confirmation@example.invalid", "https://example.invalid"),
                        tokens,
                        sessions,
                        clock);
    }

    /** A pending registration of {@link #EMAIL} with a code, which is returned. */
    private String pendingRegistration() {
        var id = UUID.randomUUID();
        var operation = UUID.randomUUID();
        Instant now = clock.instant();
        registrations.insert(
                id,
                EMAIL,
                "empreinte-du-mot-de-passe",
                AuthenticationSecrets.hash(KEY),
                now.plus(RegistrationPage.LIFETIME));
        registrations.addVerification(
                operation,
                id,
                AuthenticationSecrets.hash(tokens.codeCheck(operation, tokens.code(operation))),
                tokens.keyId(),
                now,
                now.plus(RegistrationCode.VALIDITY));
        return tokens.code(operation);
    }

    private void confirm(String code) {
        transactions.confirm(EMAIL, code, KEY, SessionPersistence.UNTIL_BROWSER_CLOSES, null, null);
    }

    private AuthenticationMail start() {
        return transactions.start(EMAIL, "empreinte", KEY);
    }

    @Test
    void aRequestForAFreeEmailDecidesTheMailOfAConfirmationCode() {
        var mail = start();

        assertThat(mail.flow()).as("flow").isEqualTo(MailFlow.CONFIRMATION);
        assertThat(mail.kind()).as("kind").isEqualTo("CONFIRMATION");
        assertThat(mail.recipient()).as("recipient").isEqualTo(EMAIL);
        assertThat(mail.keyId()).as("key").isEqualTo(tokens.keyId());
        assertThat(registrations.verification(mail.operationId()))
                .as("verification of the code")
                .isNotNull();
    }

    @Test
    void aRequestForAnEmailOfAPasswordAccountDecidesThePasswordNotice() {
        accounts.save(Account.confirmed("autre", EMAIL, "empreinte"));

        var mail = start();

        assertThat(mail.kind()).as("kind").isEqualTo("ACCOUNT_PASSWORD");
        assertThat(mail.operationId()).as("operation").isNull();
    }

    @Test
    void aRequestForAnEmailOfAGoogleAccountDecidesTheGoogleNotice() {
        var account = accounts.save(Account.confirmed("autre", EMAIL, null));
        google.link("sujet-google", account.id());

        var mail = start();

        assertThat(mail.kind()).as("kind").isEqualTo("ACCOUNT_GOOGLE");
    }

    @Test
    void aRequestForAnEmailOfAnAccountWithBothWaysDecidesTheNoticeOfBoth() {
        var account = accounts.save(Account.confirmed("autre", EMAIL, "empreinte"));
        google.link("sujet-google", account.id());

        var mail = start();

        assertThat(mail.kind()).as("kind").isEqualTo("ACCOUNT_BOTH");
    }

    @Test
    void aRequestForAnEmailThatHasAnAccountKeepsNoRegistration() {
        accounts.save(Account.confirmed("autre", EMAIL, "empreinte"));

        start();

        assertThat(registrations.byEmail(EMAIL)).as("registration").isNull();
    }

    @Test
    void aResendAfterAnAccountTookTheEmailDecidesItsNoticeAndKeepsTheRegistrationUnchanged() {
        start();
        var before = registrations.byEmail(EMAIL);
        accounts.save(Account.confirmed("google", EMAIL, "empreinte"));
        clock.now = NOW.plusSeconds(60);

        var mail = transactions.resend(EMAIL, KEY);

        assertThat(mail.kind()).as("kind").isEqualTo("ACCOUNT_PASSWORD");
        assertThat(mail.operationId()).as("operation").isNull();
        assertThat(registrations.byEmail(EMAIL).expiresAt())
                .as("end of the registration")
                .isEqualTo(before.expiresAt());
    }

    @Test
    void aResendForAnEmailWithNeitherAccountNorRegistrationDecidesTheGeneralNotice() {
        var mail = transactions.resend(EMAIL, KEY);

        assertThat(mail.kind()).as("kind").isEqualTo("NOTICE");
    }

    @Test
    void aNewRequestReplacesTheCodeOfTheEarlierMail() {
        var earlier = start();

        start();

        var checker = new RegistrationMailOperationChecker(registrations);
        assertThat(checker.pending(earlier.operationId(), clock.instant()))
                .as("earlier code pending")
                .isFalse();
    }

    @Test
    void aConfirmedRegistrationRecordsTheAcceptanceOfTheTermsAtTheCreationOfTheAccount() {
        var code = pendingRegistration();

        confirm(code);

        var account = accounts.findByEmailKey(EMAIL).orElseThrow();
        assertThat(accounts.termsAcceptedAt(account.id()))
                .as("terms accepted at")
                .isEqualTo(NOW);
    }

    @Test
    void aConfirmedRegistrationCreatesAnAccountThatChoosesItsUsernameNext() {
        var code = pendingRegistration();

        confirm(code);

        assertThat(accounts.findByEmailKey(EMAIL).orElseThrow().hasUsername())
                .as("account has a username")
                .isFalse();
    }

    @Test
    void anEmailTakenDuringTheInsertIsRefusedAsAccountAlreadyExists() {
        var code = pendingRegistration();
        accounts.conflictOnNextSave(Identifier.EMAIL);

        assertThatThrownBy(() -> confirm(code))
                .as("confirm")
                .isInstanceOfSatisfying(
                        RegistrationFailedException.class,
                        error ->
                                assertThat(error.reason())
                                        .as("reason")
                                        .isEqualTo(
                                                RegistrationFailedException.Reason
                                                        .ACCOUNT_ALREADY_EXISTS));
    }
}
