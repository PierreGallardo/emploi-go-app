package fr.emploigo.auth.application.google;

import static fr.emploigo.auth.application.google.GoogleFlowFixture.ADDRESS;
import static fr.emploigo.auth.application.google.GoogleFlowFixture.PASSWORD;
import static fr.emploigo.auth.application.google.GoogleFlowFixture.assertRefusedBecause;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import fr.emploigo.auth.application.failure.AuthenticationFailedException;
import fr.emploigo.auth.application.google.GoogleAuthenticationFailedException.Reason;
import fr.emploigo.auth.application.quota.LoginAttemptLimiter;
import fr.emploigo.auth.application.session.AuthenticationGrant;
import fr.emploigo.auth.application.session.SessionPersistence;
import fr.emploigo.auth.domain.Account;
import fr.emploigo.auth.domain.GoogleIdentity;
import fr.emploigo.auth.domain.SessionIdentity;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

class VerifyLocalAccountForGoogleUseCaseTest {
    private static final GoogleIdentity GMAIL =
            new GoogleIdentity("sub-verif", "jeune@gmail.com", true);

    private final GoogleFlowFixture f = new GoogleFlowFixture();

    private VerifyLocalAccountForGoogleUseCase useCase(GoogleSettings settings) {
        return new VerifyLocalAccountForGoogleUseCase(f.transactions, f.passwords, settings);
    }

    private AuthenticationGrant verify(
            GoogleTransactions.Callback pending,
            SessionIdentity current,
            String identifier,
            String password) {
        return useCase(f.settings)
                .verify(
                        pending.pendingId(),
                        pending.pendingProof(),
                        current,
                        identifier,
                        password,
                        null,
                        null,
                        ADDRESS);
    }

    @Test
    void theRightPasswordOpensASessionAndBindsItToTheFlow() {
        var owner = f.localAccount("jeune_verif", "jeune@gmail.com");
        var pending = f.pendingFor(GMAIL);

        var grant = f.verifyLocal(pending, "jeune@gmail.com");

        assertThat(grant.account().id()).as("account").isEqualTo(owner.id());
        assertThat(grant.account().username()).as("username").isEqualTo("jeune_verif");
        var stored = f.google.pending(pending.pendingId());
        assertThat(stored.accessVerified()).as("access verified").isTrue();
        assertThat(stored.scope().family()).as("flow family").isEqualTo(grant.account().familyId());
        assertThat(f.loginLimiter.forgotten())
                .as("forgotten after the successful login")
                .hasSize(1);
    }

    @Test
    void aSecondAccessOfTheSameFlowIsRefusedAndKeepsTheFirstFamily() {
        f.localAccount("jeune_double", "jeune@gmail.com");
        var pending = f.pendingFor(GMAIL);
        var first = f.verifyLocal(pending, "jeune@gmail.com");

        assertRefusedBecause(
                () -> f.verifyLocal(pending, "jeune@gmail.com"), Reason.INVALID_FLOW);
        assertThat(f.google.pending(pending.pendingId()).scope().family())
                .as("flow family")
                .isEqualTo(first.account().familyId());
    }

    @Test
    void theAccountCanBeNamedByItsUsername() {
        var owner = f.localAccount("jeune_pseudo", "jeune@gmail.com");
        var pending = f.pendingFor(GMAIL);

        var grant = verify(pending, null, "jeune_pseudo", PASSWORD);

        assertThat(grant.account().id()).as("account").isEqualTo(owner.id());
    }

    @Test
    void aWrongPasswordIsRefusedAsInvalidCredentialsAndNothingIsBound() {
        f.localAccount("jeune_mauvais", "jeune@gmail.com");
        var pending = f.pendingFor(GMAIL);

        assertThatThrownBy(() -> verify(pending, null, "jeune@gmail.com", "mauvais-mot"))
                .as("verify")
                .isInstanceOfSatisfying(
                        AuthenticationFailedException.class,
                        error ->
                                assertThat(error.reason())
                                        .as("reason")
                                        .isEqualTo(
                                                AuthenticationFailedException.Reason
                                                        .INVALID_CREDENTIALS));

        assertThat(f.google.pending(pending.pendingId()).accessVerified())
                .as("access verified")
                .isFalse();
    }

    @Test
    void anUnknownAccountIsRefusedAsInvalidCredentials() {
        f.localAccount("jeune_connu", "jeune@gmail.com");
        var pending = f.pendingFor(GMAIL);

        assertThatThrownBy(() -> verify(pending, null, "inconnu@gmail.com", PASSWORD))
                .as("verify")
                .isInstanceOfSatisfying(
                        AuthenticationFailedException.class,
                        error ->
                                assertThat(error.reason())
                                        .as("reason")
                                        .isEqualTo(
                                                AuthenticationFailedException.Reason
                                                        .INVALID_CREDENTIALS));
    }

    @Test
    void anAccountWithoutPasswordIsRefusedAsInvalidCredentials() {
        f.accounts.save(new Account("jeune_google", "sans-mot@gmail.com", null, true, true));
        var pending =
                f.pendingFor(new GoogleIdentity("sub-sans-mot", "sans-mot@gmail.com", true));

        assertThatThrownBy(() -> verify(pending, null, "sans-mot@gmail.com", PASSWORD))
                .as("verify")
                .isInstanceOfSatisfying(
                        AuthenticationFailedException.class,
                        error ->
                                assertThat(error.reason())
                                        .as("reason")
                                        .isEqualTo(
                                                AuthenticationFailedException.Reason
                                                        .INVALID_CREDENTIALS));
    }

    @Test
    void anAccountWithAnotherEmailIsRefusedAsInvalidCredentials() {
        f.localAccount("jeune_autre_mail", "autre@gmail.com");
        var pending = f.pendingFor(GMAIL);

        assertThatThrownBy(() -> verify(pending, null, "autre@gmail.com", PASSWORD))
                .as("verify")
                .isInstanceOfSatisfying(
                        AuthenticationFailedException.class,
                        error ->
                                assertThat(error.reason())
                                        .as("reason")
                                        .isEqualTo(
                                                AuthenticationFailedException.Reason
                                                        .INVALID_CREDENTIALS));
    }

    @Test
    void anAccountOtherThanTheOneOfTheFlowIsRefusedAsInvalidCredentials() {
        f.localAccount("jeune_autre_flux", "jeune@gmail.com");
        var other = f.localAccount("jeune_flux", "flux@gmail.com");
        var current =
                f.signedIn(f.sessions.create(other.id(), SessionPersistence.REMEMBERED, null, null));
        var pending = f.pendingFor(GMAIL, current);

        assertThatThrownBy(() -> verify(pending, current, "jeune@gmail.com", PASSWORD))
                .as("verify")
                .isInstanceOfSatisfying(
                        AuthenticationFailedException.class,
                        error ->
                                assertThat(error.reason())
                                        .as("reason")
                                        .isEqualTo(
                                                AuthenticationFailedException.Reason
                                                        .INVALID_CREDENTIALS));
    }

    @Test
    void anAccountAlreadyLinkedToAnotherGoogleSubjectIsRefusedAsAlreadyLinked() {
        var owner = f.localAccount("jeune_lie_compte", "jeune@gmail.com");
        var pending = f.pendingFor(GMAIL);
        f.google.link("sub-autre-compte", owner.id());

        assertRefusedBecause(
                () -> verify(pending, null, "jeune@gmail.com", PASSWORD), Reason.ALREADY_LINKED);
    }

    @Test
    void aGoogleSubjectAlreadyLinkedToAnotherAccountIsRefusedAsAlreadyLinked() {
        f.localAccount("jeune_sujet_lie", "jeune@gmail.com");
        var other = f.localAccount("jeune_autre_sujet", "autre-sujet@gmail.com");
        var pending = f.pendingFor(GMAIL);
        f.google.link(GMAIL.subject(), other.id());

        assertRefusedBecause(
                () -> verify(pending, null, "jeune@gmail.com", PASSWORD), Reason.ALREADY_LINKED);
    }

    @Test
    void aDisabledGoogleIsRefusedBeforeAnyPasswordCheck() {
        f.localAccount("jeune_desactive_verif", "jeune@gmail.com");
        var pending = f.pendingFor(GMAIL);
        var disabled = new GoogleSettings(false, GoogleFlowFixture.ORIGIN);

        assertRefusedBecause(
                () ->
                        useCase(disabled)
                                .verify(
                                        pending.pendingId(),
                                        pending.pendingProof(),
                                        null,
                                        "jeune@gmail.com",
                                        PASSWORD,
                                        null,
                                        null,
                                        ADDRESS),
                Reason.UNAVAILABLE);

        assertThat(f.loginLimiter.begun()).as("attempts begun").isEmpty();
    }

    @Test
    void aRefusedLoginQuotaIsReportedBeforeAnySession() {
        f.localAccount("jeune_quota_verif", "jeune@gmail.com");
        var pending = f.pendingFor(GMAIL);
        f.loginLimiter.answer(new LoginAttemptLimiter.Limited(Duration.ofMinutes(5)));

        assertThatThrownBy(() -> verify(pending, null, "jeune@gmail.com", PASSWORD))
                .as("verify")
                .isInstanceOfSatisfying(
                        AuthenticationFailedException.class,
                        error ->
                                assertThat(error.reason())
                                        .as("reason")
                                        .isEqualTo(
                                                AuthenticationFailedException.Reason.RATE_LIMITED));

        assertThat(f.google.pending(pending.pendingId()).accessVerified())
                .as("access verified")
                .isFalse();
    }

    @Test
    void anExpiredFlowIsRefusedAsExpired() {
        f.localAccount("jeune_expire_verif", "jeune@gmail.com");
        var pending = f.pendingFor(GMAIL);
        f.clock.now = f.clock.now.plus(Duration.ofMinutes(11));

        assertRefusedBecause(
                () -> verify(pending, null, "jeune@gmail.com", PASSWORD), Reason.EXPIRED_FLOW);
    }

    @Test
    void anUnknownFlowIsRefusedAsInvalidFlow() {
        f.localAccount("jeune_flux_inconnu", "jeune@gmail.com");

        assertRefusedBecause(
                () ->
                        useCase(f.settings)
                                .verify(
                                        UUID.randomUUID(),
                                        "pas-de-flux",
                                        null,
                                        "jeune@gmail.com",
                                        PASSWORD,
                                        null,
                                        null,
                                        ADDRESS),
                Reason.INVALID_FLOW);
    }
}
