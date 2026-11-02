package fr.emploigo.auth.application.session;

import static org.assertj.core.api.Assertions.assertThat;

import fr.emploigo.auth.application.account.FakeAccountRepository;
import fr.emploigo.auth.domain.Account;
import fr.emploigo.auth.domain.AuthenticationSecrets;
import fr.emploigo.support.TestClock;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

class ReconnectionRevokerTest {
    private static final Instant NOW = Instant.parse("2026-10-17T10:00:00Z");

    private final TestClock clock = new TestClock();
    private final FakeAccountRepository accounts = new FakeAccountRepository();
    private final FakeReconnectionRepository reconnections = new FakeReconnectionRepository();
    private final AuthenticationSessions sessions =
            AuthenticationSessionsFixture.of(
                    accounts, reconnections, clock, new FakeBrowserProofRepository());
    private final ReconnectionRevoker revoker =
            new ReconnectionRevoker(
                    reconnections, new ReconnectionTransactions(accounts, reconnections, clock));

    @BeforeEach
    void startAtAFixedInstant() {
        clock.now = NOW;
    }

    private AuthenticationGrant session(String username) {
        var account =
                accounts.save(
                        new Account(
                                username, username + "@example.invalid", "empreinte", true, true));
        return sessions.create(account.id(), SessionPersistence.REMEMBERED, null, null);
    }

    private boolean revoked(UUID family) {
        return reconnections.find(family).revoked();
    }

    @Test
    void theFamilyNamedByTheCookieIsRevoked() {
        var grant = session("jeune_cookie");

        revoker.revoke(grant.reconnectionCookie(), null);

        assertThat(revoked(grant.account().familyId())).as("revoked").isTrue();
    }

    @Test
    void theAuthenticatedFamilyIsRevokedWithoutACookie() {
        var grant = session("jeune_session_sans_cookie");

        revoker.revoke(null, grant.account().familyId());

        assertThat(revoked(grant.account().familyId())).as("revoked").isTrue();
    }

    @Test
    void bothFamiliesAreRevokedWhenTheCookieAndTheSessionDiffer() {
        var cookie = session("jeune_cookie_deux");
        var authenticated = session("jeune_session_deux");

        revoker.revoke(cookie.reconnectionCookie(), authenticated.account().familyId());

        assertThat(revoked(cookie.account().familyId())).as("cookie family").isTrue();
        assertThat(revoked(authenticated.account().familyId()))
                .as("session family")
                .isTrue();
    }

    @Test
    void aCookieWithThePreviousSecretRevokesItsFamily() {
        var grant = session("jeune_rotation");
        var family = grant.account().familyId();
        reconnections.rotate(
                reconnections.find(family),
                AuthenticationSecrets.hash("nouveau-secret"),
                NOW.plusSeconds(3600),
                NOW);

        revoker.revoke(grant.reconnectionCookie(), null);

        assertThat(revoked(family)).as("revoked").isTrue();
    }

    @Test
    void aCookieWithAnotherSecretRevokesNothing() {
        var grant = session("jeune_faux_secret");
        var family = grant.account().familyId();

        revoker.revoke(family + "." + AuthenticationSecrets.generate(), null);

        assertThat(revoked(family)).as("revoked").isFalse();
    }

    @Test
    void aCookieOfAnUnknownFamilyRevokesNothing() {
        var grant = session("jeune_famille_absente");
        var family = grant.account().familyId();

        revoker.revoke(UUID.randomUUID() + "." + AuthenticationSecrets.generate(), null);

        assertThat(revoked(family)).as("revoked").isFalse();
    }

    @Test
    void aMalformedCookieStillRevokesTheAuthenticatedFamily() {
        var grant = session("jeune_cookie_casse");

        revoker.revoke("pas-un-cookie", grant.account().familyId());

        assertThat(revoked(grant.account().familyId())).as("revoked").isTrue();
    }

    @Test
    void aMalformedCookieOpensNoLookup() {
        session("jeune_lecture_refusee");

        revoker.revoke("pas-un-cookie", null);

        assertThat(reconnections.lookups()).as("lookups").isZero();
    }

    @Test
    void noCookieAndNoSessionOpensNoLookup() {
        session("jeune_rien");

        revoker.revoke(null, null);

        assertThat(reconnections.lookups()).as("lookups").isZero();
    }
}
