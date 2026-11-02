package fr.emploigo.auth.application.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import fr.emploigo.auth.application.account.FakeAccountRepository;
import fr.emploigo.auth.application.failure.AuthenticationFailedException;
import fr.emploigo.auth.domain.Account;
import fr.emploigo.support.TestClock;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

class GetSessionUseCaseTest {
    private static final Instant NOW = Instant.parse("2026-10-17T10:00:00Z");

    private final TestClock clock = new TestClock();
    private final FakeAccountRepository accounts = new FakeAccountRepository();
    private final FakeReconnectionRepository reconnections = new FakeReconnectionRepository();
    private final AuthenticationSessions sessions =
            AuthenticationSessionsFixture.of(
                    accounts, reconnections, clock, new FakeBrowserProofRepository());
    private final GetSessionUseCase useCase = new GetSessionUseCase(sessions);

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

    private static void assertUnauthenticated(ThrowingCallable call) {
        assertThatThrownBy(call)
                .as("session")
                .isInstanceOfSatisfying(
                        AuthenticationFailedException.class,
                        error ->
                                assertThat(error.reason())
                                        .as("reason")
                                        .isEqualTo(
                                                AuthenticationFailedException.Reason
                                                        .UNAUTHENTICATED));
    }

    @Test
    void aLiveSessionReturnsItsAccountAndFamily() {
        var grant = session("jeune_vivant");

        var account =
                useCase.get(
                        grant.account().id(),
                        grant.account().credentialVersion(),
                        grant.account().familyId());

        assertThat(account).as("account").isEqualTo(grant.account());
    }

    @Test
    void anUnknownAccountIsUnauthenticated() {
        assertUnauthenticated(() -> useCase.get(999L, 0, UUID.randomUUID()));
    }

    @Test
    void aDisabledAccountIsUnauthenticated() {
        var grant = session("jeune_desactive");
        var stored = accounts.findById(grant.account().id()).orElseThrow();
        accounts.store(
                new Account(
                        stored.id(),
                        stored.publicId(),
                        stored.username(),
                        stored.emailKey(),
                        stored.passwordHash(),
                        stored.emailVerified(),
                        false,
                        stored.role(),
                        stored.credentialVersion()));

        assertUnauthenticated(
                () ->
                        useCase.get(
                                grant.account().id(),
                                grant.account().credentialVersion(),
                                grant.account().familyId()));
    }

    @Test
    void aChangedPasswordVersionIsUnauthenticated() {
        var grant = session("jeune_mot_de_passe");
        accounts.changePassword(grant.account().id(), "nouvelle-empreinte");

        assertUnauthenticated(
                () ->
                        useCase.get(
                                grant.account().id(),
                                grant.account().credentialVersion(),
                                grant.account().familyId()));
    }

    @Test
    void aRevokedFamilyIsUnauthenticated() {
        var grant = session("jeune_revoque");
        reconnections.revoke(grant.account().familyId());

        assertUnauthenticated(
                () ->
                        useCase.get(
                                grant.account().id(),
                                grant.account().credentialVersion(),
                                grant.account().familyId()));
    }

    @Test
    void aFamilyPastItsThirtyDaysIsUnauthenticated() {
        var grant = session("jeune_expire");
        clock.now = NOW.plus(AuthenticationSessions.RECONNECTION_DURATION).plusSeconds(1);

        assertUnauthenticated(
                () ->
                        useCase.get(
                                grant.account().id(),
                                grant.account().credentialVersion(),
                                grant.account().familyId()));
    }

    @Test
    void aMissingFamilyIsUnauthenticated() {
        var grant = session("jeune_sans_famille");

        assertUnauthenticated(
                () ->
                        useCase.get(
                                grant.account().id(),
                                grant.account().credentialVersion(),
                                null));
    }

    @Test
    void anUnknownFamilyIsUnauthenticated() {
        var grant = session("jeune_famille_inconnue");

        assertUnauthenticated(
                () ->
                        useCase.get(
                                grant.account().id(),
                                grant.account().credentialVersion(),
                                UUID.randomUUID()));
    }

    @Test
    void aFamilyOfAnotherAccountIsUnauthenticated() {
        var first = session("jeune_premier");
        var second = session("jeune_second");

        assertUnauthenticated(
                () ->
                        useCase.get(
                                second.account().id(),
                                second.account().credentialVersion(),
                                first.account().familyId()));
    }
}
