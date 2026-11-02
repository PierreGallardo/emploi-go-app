package fr.emploigo.auth.application.google;

import static fr.emploigo.auth.application.google.GoogleFlowFixture.ADDRESS;
import static fr.emploigo.auth.application.google.GoogleFlowFixture.assertRefusedBecause;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import fr.emploigo.auth.application.failure.AuthenticationFailedException;
import fr.emploigo.auth.application.google.GoogleAuthenticationFailedException.Reason;
import fr.emploigo.auth.application.quota.AuthenticationRateLimiter.Cause;
import fr.emploigo.auth.application.quota.AuthenticationRateLimiter.Limited;
import fr.emploigo.auth.application.quota.FakeAuthenticationRateLimiter.Counted;
import fr.emploigo.auth.application.quota.QuotaScope;
import fr.emploigo.auth.application.session.AuthenticationGrant;
import fr.emploigo.auth.application.session.SessionPersistence;
import fr.emploigo.auth.domain.GoogleIdentity;
import fr.emploigo.auth.domain.IdentifierTakenException.Identifier;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

class SignUpWithGoogleUseCaseTest {
    private static final GoogleIdentity GMAIL =
            new GoogleIdentity("sub-inscription", "nouveau@gmail.com", true);
    private static final GoogleIdentity UNCONFIRMED =
            new GoogleIdentity("sub-inscription-2", "nouveau@example.invalid", false);

    private final GoogleFlowFixture f = new GoogleFlowFixture();

    private SignUpWithGoogleUseCase useCase(GoogleSettings settings) {
        return new SignUpWithGoogleUseCase(f.transactions, f.quota, settings);
    }

    private AuthenticationGrant signUp(GoogleTransactions.Callback pending, String username) {
        return useCase(f.settings)
                .signUp(
                        pending.pendingId(),
                        pending.pendingProof(),
                        null,
                        username,
                        null,
                        null,
                        ADDRESS);
    }

    @Test
    void aNewAccountIsCreatedWithTheChosenUsernameAndTheGoogleEmail() {
        var pending = f.pendingFor(GMAIL);

        var grant = signUp(pending, "nouveau_jeune");

        assertThat(grant.account().username()).as("username").isEqualTo("nouveau_jeune");
        var created = f.accounts.findByEmailKey("nouveau@gmail.com");
        assertThat(created).as("account").isPresent();
        assertThat(created.get().id()).as("id").isEqualTo(grant.account().id());
        assertThat(created.get().enabled()).as("enabled").isTrue();
        assertThat(created.get().emailVerified()).as("email verified").isTrue();
        assertThat(created.get().local()).as("has a password").isFalse();
    }

    @Test
    void theNewAccountRecordsTheAcceptanceOfTheTermsAtItsCreation() {
        var pending = f.pendingFor(GMAIL);

        var grant = signUp(pending, "nouveau_cgu");

        assertThat(f.accounts.termsAcceptedAt(grant.account().id()))
                .as("terms accepted at")
                .isEqualTo(f.clock.instant());
    }

    @Test
    void theGoogleSubjectIsLinkedToTheNewAccount() {
        var pending = f.pendingFor(GMAIL);

        var grant = signUp(pending, "nouveau_lie");

        assertThat(f.google.linkedAccount("sub-inscription"))
                .as("linked account")
                .isEqualTo(grant.account().id());
    }

    @Test
    void theFlowIsFinishedOnceTheAccountExists() {
        var pending = f.pendingFor(GMAIL);

        signUp(pending, "nouveau_fini");

        assertThat(f.google.pending(pending.pendingId()).consumedAt())
                .as("consumed at")
                .isNotNull();
    }

    @Test
    void theLocalRegistrationPendingForTheEmailIsClosedWithItsPassword() {
        UUID registration = UUID.randomUUID();
        f.registrations.insert(
                registration,
                "nouveau@gmail.com",
                "empreinte-du-mot-de-passe",
                "empreinte-de-la-cle",
                f.clock.instant().plus(Duration.ofHours(24)));
        var pending = f.pendingFor(GMAIL);

        signUp(pending, "nouveau_google");

        var closed = f.registrations.lockById(registration);
        assertThat(closed.status()).as("status").isEqualTo("EXPIRED");
        assertThat(closed.passwordHash()).as("password hash").isNull();
        assertThat(closed.confirmationKeyHash()).as("key of the page").isNull();
    }

    @Test
    void theNewSessionReplacesTheSessionOfTheBrowser() {
        var old = f.localAccount("ancien_jeune", "ancien@gmail.com");
        var previous = f.sessions.create(old.id(), SessionPersistence.REMEMBERED, null, null);
        var pending = f.pendingFor(GMAIL);

        useCase(f.settings)
                .signUp(
                        pending.pendingId(),
                        pending.pendingProof(),
                        null,
                        "nouveau_remplace",
                        previous.reconnectionCookie(),
                        previous.account().familyId(),
                        ADDRESS);

        assertThat(f.reconnections.find(previous.account().familyId()).revoked())
                .as("previous family revoked")
                .isTrue();
    }

    @Test
    void aUsernameOutsideTheRuleIsRefusedAsInvalidUsername() {
        var pending = f.pendingFor(GMAIL);

        assertRefusedBecause(() -> signUp(pending, "nouveau-jeune"), Reason.INVALID_USERNAME);

        assertThat(f.accounts.findByEmailKey("nouveau@gmail.com")).as("account").isEmpty();
    }

    @Test
    void aUsernameAlreadyTakenIsRefusedAsUsernameUnavailable() {
        f.localAccount("nouveau_jeune", "autre@gmail.com");
        var pending = f.pendingFor(GMAIL);

        assertRefusedBecause(() -> signUp(pending, "nouveau_jeune"), Reason.USERNAME_UNAVAILABLE);
    }

    @Test
    void anAddressOfAnExistingAccountIsRefusedAsAccountAccessRequired() {
        f.localAccount("jeune_existant", "nouveau@gmail.com");
        var pending = f.pendingFor(GMAIL);

        assertRefusedBecause(
                () -> signUp(pending, "nouveau_doublon"), Reason.ACCOUNT_ACCESS_REQUIRED);
    }

    @Test
    void aFlowStartedWithASessionIsRefusedAsAccountAccessRequired() {
        var owner = f.localAccount("jeune_connecte", "connecte@gmail.com");
        var current =
                f.signedIn(f.sessions.create(owner.id(), SessionPersistence.REMEMBERED, null, null));
        var pending = f.pendingFor(GMAIL, current);

        assertRefusedBecause(
                () ->
                        useCase(f.settings)
                                .signUp(
                                        pending.pendingId(),
                                        pending.pendingProof(),
                                        current,
                                        "nouveau_connecte",
                                        null,
                                        null,
                                        ADDRESS),
                Reason.ACCOUNT_ACCESS_REQUIRED);
    }

    @Test
    void aGoogleSubjectAlreadyLinkedIsRefusedAsAlreadyLinked() {
        var other = f.localAccount("jeune_lie_autre", "lie@gmail.com");
        var pending = f.pendingFor(GMAIL);
        f.google.link("sub-inscription", other.id());

        assertRefusedBecause(() -> signUp(pending, "nouveau_lie_2"), Reason.ALREADY_LINKED);
    }

    @Test
    void anUnconfirmedAddressIsRefusedAsEmailConfirmationRequired() {
        var pending = f.pendingFor(UNCONFIRMED);

        assertRefusedBecause(
                () -> signUp(pending, "nouveau_non_confirme"),
                Reason.EMAIL_CONFIRMATION_REQUIRED);
    }

    @Test
    void aDisabledGoogleCreatesNoAccount() {
        var pending = f.pendingFor(GMAIL);
        var disabled = new GoogleSettings(false, GoogleFlowFixture.ORIGIN);

        assertRefusedBecause(
                () ->
                        useCase(disabled)
                                .signUp(
                                        pending.pendingId(),
                                        pending.pendingProof(),
                                        null,
                                        "nouveau_desactive",
                                        null,
                                        null,
                                        ADDRESS),
                Reason.UNAVAILABLE);

        assertThat(f.accounts.findByEmailKey("nouveau@gmail.com")).as("account").isEmpty();
    }

    @Test
    void aRefusedQuotaCreatesNoAccountAndCountsTheFlow() {
        var pending = f.pendingFor(GMAIL);
        f.limiter.answer(
                QuotaScope.GOOGLE_COMPLETE, new Limited(Duration.ofMinutes(1), Cause.VOLUME));

        assertThatThrownBy(() -> signUp(pending, "nouveau_quota"))
                .as("sign up")
                .isInstanceOf(AuthenticationFailedException.class);

        assertThat(f.accounts.findByEmailKey("nouveau@gmail.com")).as("account").isEmpty();
        assertThat(f.limiter.counted())
                .as("counted attempts")
                .contains(
                        new Counted(
                                QuotaScope.GOOGLE_COMPLETE,
                                "pending:" + pending.pendingId(),
                                ADDRESS));
    }

    @Test
    void aUsernameTakenDuringTheInsertIsRefusedAsUsernameUnavailable() {
        var pending = f.pendingFor(GMAIL);
        f.accounts.conflictOnNextSave(Identifier.USERNAME);

        assertRefusedBecause(() -> signUp(pending, "nouveau_course"), Reason.USERNAME_UNAVAILABLE);
    }

    @Test
    void anEmailTakenDuringTheInsertIsRefusedAsAccountAccessRequired() {
        var pending = f.pendingFor(GMAIL);
        f.accounts.conflictOnNextSave(Identifier.EMAIL);

        assertRefusedBecause(
                () -> signUp(pending, "nouveau_course_mail"), Reason.ACCOUNT_ACCESS_REQUIRED);
    }

    @Test
    void aGoogleSubjectTakenDuringTheLinkIsRefusedAsAlreadyLinked() {
        var pending = f.pendingFor(GMAIL);
        f.google.conflictOnNextLink(Identifier.GOOGLE_SUBJECT);

        assertRefusedBecause(() -> signUp(pending, "nouveau_course_sujet"), Reason.ALREADY_LINKED);
    }

    @Test
    void aGoogleAccountTakenDuringTheLinkIsRefusedAsAlreadyLinked() {
        var pending = f.pendingFor(GMAIL);
        f.google.conflictOnNextLink(Identifier.GOOGLE_ACCOUNT);

        assertRefusedBecause(() -> signUp(pending, "nouveau_course_compte"), Reason.ALREADY_LINKED);
    }
}
