package fr.emploigo.auth.application.google;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import fr.emploigo.auth.application.account.FakeAccountRepository;
import fr.emploigo.auth.application.confirmation.ConfirmationKeySettings;
import fr.emploigo.auth.application.confirmation.ConfirmationTokens;
import fr.emploigo.auth.application.mail.AuthenticationMailDispatcher;
import fr.emploigo.auth.application.mail.AuthenticationMailRenderer;
import fr.emploigo.auth.application.mail.AuthenticationMailSettings;
import fr.emploigo.auth.application.mail.FakeAuthenticationMailSender;
import fr.emploigo.auth.application.password.FakePasswordEncoder;
import fr.emploigo.auth.application.session.PasswordAuthenticator;
import fr.emploigo.auth.application.password.PasswordHasher;
import fr.emploigo.auth.application.quota.FakeAuthenticationRateLimiter;
import fr.emploigo.auth.application.quota.FakeLoginAttemptLimiter;
import fr.emploigo.auth.application.quota.QuotaEnforcer;
import fr.emploigo.auth.application.recovery.FakePasswordRecoveryRepository;
import fr.emploigo.auth.application.recovery.RecoveryMailOperationChecker;
import fr.emploigo.auth.application.registration.FakeRegistrationRepository;
import fr.emploigo.auth.application.registration.RegistrationMailOperationChecker;
import fr.emploigo.auth.application.session.AuthenticationGrant;
import fr.emploigo.auth.application.session.AuthenticationSessions;
import fr.emploigo.auth.application.session.AuthenticationSessionsFixture;
import fr.emploigo.auth.application.session.FakeReconnectionRepository;
import fr.emploigo.auth.domain.Account;
import fr.emploigo.auth.domain.AuthenticationSecrets;
import fr.emploigo.auth.domain.GoogleIdentity;
import fr.emploigo.auth.domain.SessionIdentity;
import fr.emploigo.auth.infrastructure.quota.QuotaTestProperties;
import fr.emploigo.auth.infrastructure.session.SpringSecurityPasswordChecker;
import fr.emploigo.support.TestClock;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.springframework.mock.env.MockEnvironment;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

/**
 * The graph of the Google flows, with the real services over the fakes of their ports. A test
 * drives it through the same calls as the controllers: it starts a flow, then the use cases.
 */
final class GoogleFlowFixture {
    static final Instant NOW = Instant.parse("2026-10-17T10:00:00Z");
    static final String ORIGIN = "https://example.invalid";
    static final String SENDER = "confirmation@example.invalid";
    static final String ADDRESS = "203.0.113.7";
    static final String PASSWORD = "mot-de-passe-du-jeune";
    static final String STATE = "etat-du-parcours";
    static final String BROWSER_PROOF = AuthenticationSecrets.generate();
    private static final byte[] TOKEN_KEY =
            "cle-de-test-des-jetons-de-confirmation".getBytes(StandardCharsets.UTF_8);

    final TestClock clock = new TestClock();
    final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    final FakeAccountRepository accounts = new FakeAccountRepository();
    final FakeReconnectionRepository reconnections = new FakeReconnectionRepository();
    final FakeGoogleRepository google = new FakeGoogleRepository();
    final FakeRegistrationRepository registrations = new FakeRegistrationRepository();
    final FakePasswordRecoveryRepository recoveries = new FakePasswordRecoveryRepository();
    final FakeAuthenticationMailSender transport = new FakeAuthenticationMailSender();
    final FakeAuthenticationRateLimiter limiter = new FakeAuthenticationRateLimiter();
    final FakeLoginAttemptLimiter loginLimiter = new FakeLoginAttemptLimiter();
    final GoogleSettings settings = new GoogleSettings(true, ORIGIN);
    final ConfirmationTokens tokens =
            new ConfirmationTokens(new ConfirmationKeySettings(true, TOKEN_KEY));
    final AuthenticationSessions sessions;
    final QuotaEnforcer quota;
    final AuthenticationMailDispatcher dispatcher;
    final GoogleTransactions transactions;
    final PasswordHasher hashing;
    final PasswordAuthenticator passwords;

    GoogleFlowFixture() {
        clock.now = NOW;
        var mail = new AuthenticationMailSettings(true, SENDER, ORIGIN);
        sessions = AuthenticationSessionsFixture.of(accounts, reconnections, clock, google);
        quota =
                new QuotaEnforcer(
                        limiter,
                        loginLimiter,
                        meters,
                        clock,
                        QuotaTestProperties.settings());
        dispatcher =
                new AuthenticationMailDispatcher(
                        new AuthenticationMailRenderer(tokens),
                        transport,
                        List.of(
                                new RegistrationMailOperationChecker(registrations),
                                new RecoveryMailOperationChecker(recoveries),
                                new GoogleMailOperationChecker(google)),
                        tokens,
                        clock);
        transactions =
                new GoogleTransactions(
                        google,
                        accounts,
                        registrations,
                        reconnections,
                        sessions,
                        mail,
                        tokens,
                        clock);
        hashing = new PasswordHasher(new FakePasswordEncoder(), meters, new MockEnvironment());
        passwords =
                new PasswordAuthenticator(
                        new SpringSecurityPasswordChecker(accounts, hashing), quota);
    }

    /** A flow started without a session, accepted for {@code identity}: its pending link. */
    GoogleTransactions.Callback pendingFor(GoogleIdentity identity) {
        return pendingFor(identity, null);
    }

    /**
     * A flow started with {@code current} as its session, accepted for {@code identity}. Only one
     * flow per browser may be current: a test starts one.
     */
    GoogleTransactions.Callback pendingFor(GoogleIdentity identity, SessionIdentity current) {
        transactions.start(STATE, BROWSER_PROOF, current);
        var scope = transactions.consume(STATE, BROWSER_PROOF, current);
        return transactions.accept(
                identity, scope, current, STATE, null, null);
    }

    /** Checks the password of {@code identifier} for the pending link, as the page does. */
    AuthenticationGrant verifyLocal(GoogleTransactions.Callback pending, String identifier) {
        return new VerifyLocalAccountForGoogleUseCase(transactions, passwords, settings)
                .verify(
                        pending.pendingId(),
                        pending.pendingProof(),
                        null,
                        identifier,
                        PASSWORD,
                        null,
                        null,
                        ADDRESS);
    }

    /** The session of a grant, as the filter reads it on the next request. */
    SessionIdentity signedIn(AuthenticationGrant grant) {
        return new SessionIdentity(
                grant.account().id(),
                grant.account().credentialVersion(),
                grant.account().familyId(),
                clock.instant(),
                true);
    }

    /** A local account with {@link #PASSWORD}, saved with its id. */
    Account localAccount(String username, String email) {
        return accounts.save(new Account(username, email, hashing.encode(PASSWORD), true, true));
    }

    static void assertRefusedBecause(
            ThrowingCallable call, GoogleAuthenticationFailedException.Reason reason) {
        assertThatThrownBy(call)
                .as("refusal")
                .isInstanceOfSatisfying(
                        GoogleAuthenticationFailedException.class,
                        error -> assertThat(error.reason()).as("reason").isEqualTo(reason));
    }
}
