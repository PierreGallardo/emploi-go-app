package fr.emploigo.auth.application.google;

import static fr.emploigo.auth.application.google.GoogleFlowFixture.assertRefusedBecause;
import static org.assertj.core.api.Assertions.assertThat;

import fr.emploigo.auth.application.google.GoogleAuthenticationFailedException.Reason;
import fr.emploigo.auth.application.google.GoogleTransactions.Stage;
import fr.emploigo.auth.application.session.SessionPersistence;
import fr.emploigo.auth.domain.GoogleIdentity;
import fr.emploigo.auth.domain.SessionIdentity;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

class ReadGoogleStageUseCaseTest {
    private static final GoogleIdentity GMAIL =
            new GoogleIdentity("sub-etape", "jeune@gmail.com", true);
    private static final GoogleIdentity UNCONFIRMED =
            new GoogleIdentity("sub-etape-2", "jeune@example.invalid", false);

    private final GoogleFlowFixture f = new GoogleFlowFixture();

    private Stage stageOf(GoogleTransactions.Callback pending, SessionIdentity current) {
        return new ReadGoogleStageUseCase(f.transactions)
                .read(pending.pendingId(), pending.pendingProof(), current);
    }

    @Test
    void anUnconfirmedAddressAsksForItsEmailLink() {
        var pending = f.pendingFor(UNCONFIRMED);

        assertThat(stageOf(pending, null))
                .as("stage")
                .isEqualTo(Stage.EMAIL_CONFIRMATION_REQUIRED);
    }

    @Test
    void aConfirmedAddressWithoutAnAccountAsksForAUsername() {
        var pending = f.pendingFor(GMAIL);

        assertThat(stageOf(pending, null)).as("stage").isEqualTo(Stage.CHOOSE_USERNAME);
    }

    @Test
    void aConfirmedAddressOfAnExistingAccountAsksForItsAccountAccess() {
        f.localAccount("jeune_etape", "jeune@gmail.com");
        var pending = f.pendingFor(GMAIL);

        assertThat(stageOf(pending, null))
                .as("stage")
                .isEqualTo(Stage.ACCOUNT_ACCESS_REQUIRED);
    }

    @Test
    void aFlowStartedWithASessionAsksForItsAccountAccess() {
        var owner = f.localAccount("jeune_session", "session@gmail.com");
        var current =
                f.signedIn(f.sessions.create(owner.id(), SessionPersistence.REMEMBERED, null, null));
        var pending =
                f.pendingFor(new GoogleIdentity("sub-etape-3", "jeune@gmail.com", true), current);

        assertThat(stageOf(pending, current))
                .as("stage")
                .isEqualTo(Stage.ACCOUNT_ACCESS_REQUIRED);
    }

    @Test
    void aFlowWithAVerifiedAccessAsksForTheLinkConfirmation() {
        f.localAccount("jeune_acces", "jeune@gmail.com");
        var pending = f.pendingFor(GMAIL);
        var current = f.signedIn(f.verifyLocal(pending, "jeune@gmail.com"));

        assertThat(stageOf(pending, current)).as("stage").isEqualTo(Stage.CONFIRM_LINK);
    }

    @Test
    void anAddressConfirmedByItsEmailLinkMovesOnToTheUsername() {
        var pending = f.pendingFor(UNCONFIRMED);
        var verification = UUID.fromString("a3f1c0de-2b4d-4e6f-8a9b-0c1d2e3f4a5b");
        f.google.prepareVerification(
                verification,
                UNCONFIRMED,
                "empreinte-de-la-confirmation",
                f.tokens.keyId(),
                f.clock.instant().plus(Duration.ofHours(24)));
        f.google.confirm(verification, f.clock.instant());

        assertThat(stageOf(pending, null)).as("stage").isEqualTo(Stage.CHOOSE_USERNAME);
    }

    @Test
    void aFlowOfAnotherBrowserIsRefusedAsInvalidFlow() {
        var pending = f.pendingFor(GMAIL);

        assertRefusedBecause(
                () ->
                        new ReadGoogleStageUseCase(f.transactions)
                                .read(pending.pendingId(), "autre-navigateur", null),
                Reason.INVALID_FLOW);
    }

    @Test
    void anUnknownFlowIsRefusedAsInvalidFlow() {
        assertRefusedBecause(
                () ->
                        new ReadGoogleStageUseCase(f.transactions)
                                .read(UUID.randomUUID(), "pas-de-flux", null),
                Reason.INVALID_FLOW);
    }

    @Test
    void aFlowOfAnotherSessionIsRefusedAsInvalidFlow() {
        var pending = f.pendingFor(GMAIL);
        var other = new SessionIdentity(99L, 0, UUID.randomUUID(), f.clock.instant(), true);

        assertRefusedBecause(() -> stageOf(pending, other), Reason.INVALID_FLOW);
    }

    @Test
    void anExpiredFlowIsRefusedAsExpired() {
        var pending = f.pendingFor(GMAIL);
        f.clock.now = f.clock.now.plus(Duration.ofMinutes(11));

        assertRefusedBecause(() -> stageOf(pending, null), Reason.EXPIRED_FLOW);
    }
}
