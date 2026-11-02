package fr.emploigo.auth.application.session;

import fr.emploigo.auth.application.session.ReconnectionRepository.Reconnection;
import fr.emploigo.auth.domain.AuthenticationSecrets;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * The reconnection cookie as read: the family it names and the secret it carries. A malformed
 * cookie is refused before the database is touched, so it takes no pool connection.
 */
record ReconnectionToken(UUID id, String secret) {
    @Override
    public String toString() {
        return "ReconnectionToken[redacted]";
    }

    /**
     * The token of {@code cookie}, or nothing when the cookie is absent or not a family id, a dot
     * and a secret.
     */
    static Optional<ReconnectionToken> parse(String cookie) {
        return AuthenticationToken.parse(cookie)
                .map(token -> new ReconnectionToken(token.id(), token.secret()));
    }

    boolean live(Reconnection row, Instant now) {
        return row != null && !row.revoked() && row.expiresAt().isAfter(now);
    }

    boolean usable(Reconnection row, Instant now) {
        return live(row, now) && AuthenticationSecrets.matches(secret, row.secretHash());
    }

    /**
     * The secret is the current one, or the previous one replaced less than {@code tolerance} ago:
     * a response lost on the way back is not a theft.
     */
    boolean admitted(Reconnection row, Instant now, Duration tolerance) {
        return usable(row, now)
                || (replays(row, now) && !row.rotatedAt().isBefore(now.minus(tolerance)));
    }

    /**
     * The family is live and the secret of the cookie is its previous one: a rotation it did not
     * see.
     */
    boolean replays(Reconnection row, Instant now) {
        return live(row, now) && AuthenticationSecrets.matches(secret, row.previousHash());
    }

    /** The secret is the current or the previous one of the family, live or not. */
    boolean belongsTo(Reconnection row) {
        return AuthenticationSecrets.matches(secret, row.secretHash())
                || AuthenticationSecrets.matches(secret, row.previousHash());
    }
}
