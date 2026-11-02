package fr.emploigo.auth.application.session;

import fr.emploigo.auth.application.failure.AuthenticationFailedException;
import fr.emploigo.auth.application.quota.QuotaEnforcer;
import fr.emploigo.auth.application.quota.QuotaScope;
import fr.emploigo.config.ApiOnly;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/** Recreates a session from its reconnection cookie, and renews the secret of that cookie. */
@ApiOnly
@Service
public class ResumeSessionUseCase {
    private static final String REUSE_REVOKED = "auth.reconnection.reuse.revoked";
    private static final Logger LOG = LoggerFactory.getLogger(ResumeSessionUseCase.class);
    private final ReconnectionRepository reconnections;
    private final ReconnectionTransactions transactions;
    private final QuotaEnforcer quota;
    private final Clock clock;
    private final Duration reuseTolerance;
    private final Counter reuseRevoked;

    public ResumeSessionUseCase(
            ReconnectionRepository reconnections,
            ReconnectionTransactions transactions,
            QuotaEnforcer quota,
            Clock clock,
            MeterRegistry meters,
            ReconnectionSettings settings) {
        this.reconnections = reconnections;
        this.transactions = transactions;
        this.quota = quota;
        this.clock = clock;
        this.reuseTolerance = settings.reuseTolerance();
        // Registered at once, so it is visible at zero before the first revocation.
        this.reuseRevoked =
                Counter.builder(REUSE_REVOKED)
                        .description(
                                "Familles de reconnexion révoquées parce qu'un ancien secret a été rejoué après la tolérance")
                        .register(meters);
    }

    /**
     * A wrong secret is refused before any lock; a right one is counted by family before the
     * transaction, never waiting for Redis while holding a connection or a lock.
     */
    public AuthenticationGrant resume(String cookie) {
        var token =
                ReconnectionToken.parse(cookie)
                        .orElseThrow(AuthenticationFailedException::reconnectionRefused);
        var unlocked = reconnections.find(token.id());
        var now = clock.instant();
        if (!token.admitted(unlocked, now, reuseTolerance)) {
            throw refusalOf(unlocked, token, now);
        }
        quota.checkIdentity(QuotaScope.RESUME, token.id().toString());
        return transactions.rotate(token, unlocked.accountId(), reuseTolerance);
    }

    /**
     * A previous secret replayed after the tolerance revokes the family; only this first check
     * revokes, never a request that lost a rotation under the locks. The row was read without
     * lock: the reason may already be stale.
     */
    private AuthenticationFailedException refusalOf(
            ReconnectionRepository.Reconnection row, ReconnectionToken token, Instant now) {
        if (!token.replays(row, now)) return AuthenticationFailedException.reconnectionRefused();
        if (transactions.revokeReplayed(row.id(), row.previousHash(), now.minus(reuseTolerance))) {
            // After the commit of the revocation, so that neither the log nor the counter announces
            // one that was rolled back.
            reuseRevoked.increment();
            LOG.warn("Réutilisation d'un ancien secret de reconnexion : famille révoquée");
        }
        return AuthenticationFailedException.reconnectionRefused();
    }
}
