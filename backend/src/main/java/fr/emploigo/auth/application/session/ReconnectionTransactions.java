package fr.emploigo.auth.application.session;

import fr.emploigo.auth.application.account.AccountRepository;
import fr.emploigo.auth.application.failure.AuthenticationFailedException;
import fr.emploigo.auth.domain.AuthenticationSecrets;
import fr.emploigo.config.ApiOnly;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * The writing transactions of the reconnection. {@link ResumeSessionUseCase} and {@link
 * ReconnectionRevoker} call them only after the checks that need no lock, so a refused request
 * opens none, and no Redis call runs while one is open.
 */
@ApiOnly
@Service
class ReconnectionTransactions {
    private final AccountRepository accounts;
    private final ReconnectionRepository reconnections;
    private final Clock clock;

    ReconnectionTransactions(
            AccountRepository accounts, ReconnectionRepository reconnections, Clock clock) {
        this.accounts = accounts;
        this.reconnections = reconnections;
        this.clock = clock;
    }

    /**
     * Renews the secret of the family of {@code token}, which is the current one or the previous
     * one within {@code reuseTolerance}; the current secret then becomes the previous one. The
     * checks are repeated under the locks, account first and family second, because a rotation, a
     * revocation or a reset may have committed since the first read.
     */
    @Transactional
    AuthenticationGrant rotate(ReconnectionToken token, long accountId, Duration reuseTolerance) {
        var account =
                accounts.lockById(accountId)
                        .filter(value -> value.mayAuthenticate())
                        .orElseThrow(AuthenticationFailedException::reconnectionRefused);
        var row = reconnections.lock(token.id());
        var now = clock.instant();
        boolean sameState =
                row != null
                        && row.accountId() == accountId
                        && account.credentialVersion() == row.credentialVersion();
        if (!sameState || !token.admitted(row, now, reuseTolerance)) {
            throw AuthenticationFailedException.reconnectionRefused();
        }
        accounts.recordSessionOpened(accountId, now);
        String secret = AuthenticationSecrets.generate();
        reconnections.rotate(
                row,
                AuthenticationSecrets.hash(secret),
                now.plus(AuthenticationSessions.RECONNECTION_DURATION),
                now);
        return new AuthenticationGrant(
                new AuthenticatedAccount(
                        account.id(),
                        account.publicId(),
                        account.username(),
                        account.credentialVersion(),
                        row.id()),
                row.id() + "." + secret);
    }

    /**
     * Revokes the family if {@code previousHash} is still its previous secret, replaced before
     * {@code rotatedBefore}. Returns whether this call revoked it: only the first one does.
     */
    @Transactional
    boolean revokeReplayed(UUID family, String previousHash, Instant rotatedBefore) {
        return reconnections.revokeReplayed(family, previousHash, rotatedBefore);
    }

    @Transactional
    void revoke(UUID authenticated, UUID named) {
        if (authenticated != null) reconnections.revoke(authenticated);
        if (named != null) reconnections.revoke(named);
    }
}
