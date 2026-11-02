package fr.emploigo.auth.application.session;

import fr.emploigo.auth.application.account.AccountRepository;
import fr.emploigo.auth.application.failure.AuthenticationFailedException;
import fr.emploigo.auth.domain.AuthenticationSecrets;
import fr.emploigo.config.ApiOnly;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

/**
 * Opens, checks and revokes the sessions for the other flows, the filter and the logout of Spring
 * Security; the actions of the user are the use cases beside it.
 */
@ApiOnly
@Service
public class AuthenticationSessions {
    public static final Duration RECONNECTION_DURATION = Duration.ofDays(30);
    public static final Duration SESSION_DURATION = Duration.ofHours(8);
    private final AccountRepository accounts;
    private final ReconnectionRepository reconnections;
    private final ReconnectionRevoker revoker;
    private final Clock clock;
    private final BrowserProofRepository proofs;

    public AuthenticationSessions(
            AccountRepository accounts,
            ReconnectionRepository reconnections,
            ReconnectionRevoker revoker,
            Clock clock,
            BrowserProofRepository proofs) {
        this.accounts = accounts;
        this.reconnections = reconnections;
        this.revoker = revoker;
        this.clock = clock;
        this.proofs = proofs;
    }

    public String browserProof(String sessionId) {
        return AuthenticationSecrets.browserProof(sessionId);
    }

    public void revoke(String cookie, UUID authenticatedFamily) {
        revoker.revoke(cookie, authenticatedFamily);
    }

    @Transactional
    public void principalChanged(String proof, UUID retainedFamily) {
        proofs.principalChanged(AuthenticationSecrets.hash(proof), retainedFamily);
    }

    @Transactional
    public AuthenticationGrant create(
            long accountId,
            SessionPersistence persistence,
            String previousCookie,
            UUID previousFamily) {
        return createLocked(accountId, null, persistence, previousCookie, previousFamily);
    }

    @Transactional
    public AuthenticationGrant create(
            Credentials credentials,
            SessionPersistence persistence,
            String previousCookie,
            UUID previousFamily) {
        return createLocked(
                credentials.accountId(),
                credentials.version(),
                persistence,
                previousCookie,
                previousFamily);
    }

    private AuthenticationGrant createLocked(
            long accountId,
            Long expectedVersion,
            SessionPersistence persistence,
            String previousCookie,
            UUID previousFamily) {
        // A password checked before a reset must never obtain a grant with the new version.
        var account =
                accounts.lockById(accountId)
                        .filter(
                                value ->
                                        value.mayAuthenticate()
                                                && (expectedVersion == null
                                                        || value.credentialVersion()
                                                                == expectedVersion))
                        .orElseThrow(() -> AuthenticationFailedException.invalidCredentials());
        revoker.revoke(previousCookie, previousFamily);
        UUID family = UUID.randomUUID();
        String secret = AuthenticationSecrets.generate();
        var now = clock.instant();
        accounts.recordSessionOpened(accountId, now);
        reconnections.insert(
                family,
                accountId,
                account.credentialVersion(),
                AuthenticationSecrets.hash(secret),
                now.plus(persistence.familyDuration()),
                now);
        return new AuthenticationGrant(
                new AuthenticatedAccount(
                        accountId,
                        account.publicId(),
                        account.username(),
                        account.credentialVersion(),
                        family),
                // Without the box, the family only proves the session: its secret is never sent.
                persistence == SessionPersistence.REMEMBERED ? family + "." + secret : null);
    }

    @Transactional(readOnly = true)
    public AuthenticatedAccount verify(long id, long version, UUID family) {
        var account =
                accounts.findById(id)
                        .filter(
                                value ->
                                        value.mayAuthenticate()
                                                && value.credentialVersion() == version)
                        .orElseThrow(() -> AuthenticationFailedException.unauthenticated());
        if (family == null || !reconnections.active(family, id, version, clock.instant())) {
            throw AuthenticationFailedException.unauthenticated();
        }
        return new AuthenticatedAccount(
                id, account.publicId(), account.username(), version, family);
    }
}
