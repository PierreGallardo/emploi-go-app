package fr.emploigo.auth.application.session;

import fr.emploigo.config.ApiOnly;

import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Revokes the families that name a browser: the one of its session and the one of its cookie. It
 * opens no transaction of its own, so that a cookie that is not valid takes no connection.
 */
@ApiOnly
@Service
class ReconnectionRevoker {
    private final ReconnectionRepository reconnections;
    private final ReconnectionTransactions transactions;

    ReconnectionRevoker(
            ReconnectionRepository reconnections, ReconnectionTransactions transactions) {
        this.reconnections = reconnections;
        this.transactions = transactions;
    }

    /** An invalid cookie revokes nothing and opens no transaction. */
    void revoke(String cookie, UUID authenticatedFamily) {
        UUID named = ReconnectionToken.parse(cookie).map(this::familyNamedBy).orElse(null);
        if (authenticatedFamily != null || named != null)
            transactions.revoke(authenticatedFamily, named);
    }

    /**
     * The family that {@code token} names and whose current or previous secret it carries, or
     * {@code null}.
     */
    private UUID familyNamedBy(ReconnectionToken token) {
        var row = reconnections.find(token.id());
        return row != null && token.belongsTo(row) ? row.id() : null;
    }
}
