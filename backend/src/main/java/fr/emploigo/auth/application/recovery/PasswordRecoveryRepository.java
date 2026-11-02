package fr.emploigo.auth.application.recovery;

import java.time.Instant;
import java.util.UUID;

/** The password resets, the only access to their table. */
public interface PasswordRecoveryRepository {
    /** A reset and its state. {@code consumedAt} is {@code null} while it is pending. */
    record Reset(
            UUID id,
            long accountId,
            String hash,
            String keyId,
            long version,
            Instant expiresAt,
            String status,
            Instant consumedAt) {
        @Override
        public String toString() {
            return "Reset[redacted]";
        }
    }

    /** The reset of this identifier, or {@code null} when there is none. */
    Reset reset(UUID id);

    /** A new request replaces the pending resets of its account. */
    void replace(long accountId);

    void insert(
            UUID id,
            long accountId,
            long version,
            String hash,
            String keyId,
            Instant expiry);

    /**
     * Consumes {@code reset} if it is still pending. Returns whether it was pending: a replaced or
     * used link consumes nothing. The sessions and reconnections of the account stop at the next
     * request, through the credential version that the password change increments.
     */
    boolean consume(Reset reset, Instant now);
}
