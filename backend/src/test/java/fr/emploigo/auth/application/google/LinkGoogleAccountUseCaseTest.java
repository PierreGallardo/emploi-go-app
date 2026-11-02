package fr.emploigo.auth.application.google;

import static fr.emploigo.auth.application.google.GoogleFlowFixture.ADDRESS;
import static fr.emploigo.auth.application.google.GoogleFlowFixture.assertRefusedBecause;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import fr.emploigo.auth.application.failure.AuthenticationFailedException;
import fr.emploigo.auth.application.google.GoogleAuthenticationFailedException.Reason;
import fr.emploigo.auth.application.mail.AuthenticationMailSender.Result;
import fr.emploigo.auth.application.quota.AuthenticationRateLimiter.Cause;
import fr.emploigo.auth.application.quota.AuthenticationRateLimiter.Limited;
import fr.emploigo.auth.application.quota.FakeAuthenticationRateLimiter.Counted;
import fr.emploigo.auth.application.quota.QuotaScope;
import fr.emploigo.auth.domain.GoogleIdentity;
import fr.emploigo.auth.domain.IdentifierTakenException.Identifier;
import fr.emploigo.auth.domain.SessionIdentity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Duration;

class LinkGoogleAccountUseCaseTest {
    private static final GoogleIdentity GMAIL =
            new GoogleIdentity("sub-lien", "jeune@gmail.com", true);

    private final GoogleFlowFixture f = new GoogleFlowFixture();

    private LinkGoogleAccountUseCase useCase(GoogleSettings settings) {
        return new LinkGoogleAccountUseCase(f.transactions, f.quota, f.dispatcher, settings);
    }

    private void link(GoogleTransactions.Callback pending, SessionIdentity current) {
        useCase(f.settings).link(pending.pendingId(), pending.pendingProof(), current, ADDRESS);
    }

    private SessionIdentity ownerSession(GoogleTransactions.Callback pending, String email) {
        return f.signedIn(f.verifyLocal(pending, email));
    }

    @Test
    void aVerifiedFlowLinksTheGoogleSubjectToTheOwnerAccount() {
        var owner = f.localAccount("jeune_lien", "jeune@gmail.com");
        var pending = f.pendingFor(GMAIL);
        var current = ownerSession(pending, "jeune@gmail.com");

        link(pending, current);

        assertThat(f.google.linkedAccount("sub-lien")).as("linked account").isEqualTo(owner.id());
    }

    @Test
    void aLinkedAccountIsNotifiedAfterTheLink() {
        f.localAccount("jeune_notifie", "jeune@gmail.com");
        var pending = f.pendingFor(GMAIL);
        var current = ownerSession(pending, "jeune@gmail.com");

        link(pending, current);

        assertThat(f.transport.sent()).as("messages sent").hasSize(1);
        assertThat(f.transport.sent().get(0).recipient())
                .as("recipient")
                .isEqualTo("jeune@gmail.com");
        assertThat(f.transport.sent().get(0).subject())
                .as("subject")
                .isEqualTo("Google a été associé à ton compte Emploi Go");
    }

    @ParameterizedTest
    @EnumSource(
            value = Result.class,
            names = {"FAILED", "UNCERTAIN"})
    void aNoticeTheProviderDoesNotAcceptKeepsTheLink(Result result) {
        f.localAccount("jeune_refus", "jeune@gmail.com");
        var pending = f.pendingFor(GMAIL);
        var current = ownerSession(pending, "jeune@gmail.com");
        f.transport.answer(result);

        link(pending, current);

        assertThat(f.google.linkedAccount("sub-lien")).as("linked account").isNotNull();
        assertThat(f.transport.sent()).as("messages sent").hasSize(1);
    }

    @Test
    void aDisabledGoogleRefusesTheLinkBeforeTheQuota() {
        f.localAccount("jeune_desactive", "jeune@gmail.com");
        var pending = f.pendingFor(GMAIL);
        var current = ownerSession(pending, "jeune@gmail.com");
        var disabled = new GoogleSettings(false, GoogleFlowFixture.ORIGIN);

        assertRefusedBecause(
                () ->
                        useCase(disabled)
                                .link(
                                        pending.pendingId(),
                                        pending.pendingProof(),
                                        current,
                                        ADDRESS),
                Reason.UNAVAILABLE);

        assertThat(f.limiter.counted()).as("counted attempts").isEmpty();
        assertThat(f.google.linkedAccount("sub-lien")).as("linked account").isNull();
    }

    @Test
    void aRefusedQuotaStopsTheLinkBeforeAnyChange() {
        f.localAccount("jeune_quota", "jeune@gmail.com");
        var pending = f.pendingFor(GMAIL);
        var current = ownerSession(pending, "jeune@gmail.com");
        f.limiter.answer(QuotaScope.GOOGLE_LINK, new Limited(Duration.ofMinutes(1), Cause.VOLUME));

        assertThatThrownBy(() -> link(pending, current))
                .as("link")
                .isInstanceOf(AuthenticationFailedException.class);

        assertThat(f.google.linkedAccount("sub-lien")).as("linked account").isNull();
        assertThat(f.transport.sent()).as("messages sent").isEmpty();
    }

    @Test
    void theAttemptIsCountedUnderItsPendingFlowAndTheAddress() {
        f.localAccount("jeune_compte", "jeune@gmail.com");
        var pending = f.pendingFor(GMAIL);
        var current = ownerSession(pending, "jeune@gmail.com");

        link(pending, current);

        assertThat(f.limiter.counted())
                .as("counted attempts")
                .contains(
                        new Counted(
                                QuotaScope.GOOGLE_LINK,
                                "pending:" + pending.pendingId(),
                                ADDRESS));
    }

    @Test
    void aFlowWithoutAVerifiedAccessIsRefusedAsAccountAccessRequired() {
        f.localAccount("jeune_sans_acces", "jeune@gmail.com");
        var pending = f.pendingFor(GMAIL);

        assertRefusedBecause(() -> link(pending, null), Reason.ACCOUNT_ACCESS_REQUIRED);
    }

    @Test
    void aSessionStartedMoreThanFiveMinutesAgoIsRefusedAsAccountAccessRequired() {
        f.localAccount("jeune_ancien", "jeune@gmail.com");
        var pending = f.pendingFor(GMAIL);
        var current = ownerSession(pending, "jeune@gmail.com");
        f.clock.now = f.clock.now.plus(Duration.ofMinutes(6));

        assertRefusedBecause(() -> link(pending, current), Reason.ACCOUNT_ACCESS_REQUIRED);
    }

    @Test
    void anExpiredFlowIsRefusedAsExpired() {
        f.localAccount("jeune_expire", "jeune@gmail.com");
        var pending = f.pendingFor(GMAIL);
        var current = ownerSession(pending, "jeune@gmail.com");
        f.clock.now = f.clock.now.plus(Duration.ofMinutes(11));

        assertRefusedBecause(() -> link(pending, current), Reason.EXPIRED_FLOW);
    }

    @Test
    void aGoogleSubjectAlreadyLinkedToAnotherAccountIsRefusedAsAlreadyLinked() {
        f.localAccount("jeune_lie", "jeune@gmail.com");
        var other = f.localAccount("jeune_autre", "autre@gmail.com");
        var pending = f.pendingFor(GMAIL);
        var current = ownerSession(pending, "jeune@gmail.com");
        f.google.link("sub-lien", other.id());

        assertRefusedBecause(() -> link(pending, current), Reason.ALREADY_LINKED);
    }

    @Test
    void anAccountAlreadyLinkedToAnotherGoogleSubjectIsRefusedAsAlreadyLinked() {
        var owner = f.localAccount("jeune_deja_lie", "jeune@gmail.com");
        var pending = f.pendingFor(GMAIL);
        var current = ownerSession(pending, "jeune@gmail.com");
        f.google.link("sub-autre-compte", owner.id());

        assertRefusedBecause(() -> link(pending, current), Reason.ALREADY_LINKED);
    }

    @Test
    void aGoogleSubjectTakenDuringTheLinkIsRefusedAsAlreadyLinked() {
        f.localAccount("jeune_course", "jeune@gmail.com");
        var pending = f.pendingFor(GMAIL);
        var current = ownerSession(pending, "jeune@gmail.com");
        f.google.conflictOnNextLink(Identifier.GOOGLE_SUBJECT);

        assertRefusedBecause(() -> link(pending, current), Reason.ALREADY_LINKED);
    }

    @Test
    void aGoogleAccountTakenDuringTheLinkIsRefusedAsAlreadyLinked() {
        f.localAccount("jeune_course_compte", "jeune@gmail.com");
        var pending = f.pendingFor(GMAIL);
        var current = ownerSession(pending, "jeune@gmail.com");
        f.google.conflictOnNextLink(Identifier.GOOGLE_ACCOUNT);

        assertRefusedBecause(() -> link(pending, current), Reason.ALREADY_LINKED);
    }

    @Test
    void aRefusedLinkSendsNoNotice() {
        f.localAccount("jeune_sans_avis", "jeune@gmail.com");
        var pending = f.pendingFor(GMAIL);
        var current = ownerSession(pending, "jeune@gmail.com");
        f.google.conflictOnNextLink(Identifier.GOOGLE_SUBJECT);

        assertRefusedBecause(() -> link(pending, current), Reason.ALREADY_LINKED);

        assertThat(f.transport.sent()).as("messages sent").isEmpty();
    }
}
