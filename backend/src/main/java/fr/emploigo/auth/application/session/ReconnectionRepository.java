package fr.emploigo.auth.application.session;

import java.time.Instant;
import java.util.UUID;

/**
 * The reconnection families. A read is a fresh copy of the row, or {@code null} when no family has
 * this id. The locks and the conditional updates run in the transaction of the caller.
 */
public interface ReconnectionRepository {
    record Reconnection(
            UUID id,
            long accountId,
            long credentialVersion,
            String secretHash,
            String previousHash,
            Instant expiresAt,
            Instant rotatedAt,
            boolean revoked) {
        @Override
        public String toString() {
            return "Reconnection[redacted]";
        }
    }

    void insert(
            UUID id,
            long accountId,
            long version,
            String hash,
            Instant expiresAt,
            Instant issuedAt);

    /** Takes the row lock of a family: it needs the transaction of the caller. */
    Reconnection lock(UUID id);

    Reconnection find(UUID id);

    void rotate(Reconnection previous, String hash, Instant expiresAt, Instant issuedAt);

    boolean active(UUID id, long accountId, long version, Instant now);

    void revoke(UUID id);

    /** Revokes the family if {@code previousHash} is still its previous secret, rotated before. */
    boolean revokeReplayed(UUID id, String previousHash, Instant rotatedBefore);
}
