package fr.emploigo.auth.application.registration;

import fr.emploigo.auth.application.google.LocalRegistrationRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The registrations and the codes they issue. Every read is a fresh copy from the database, so a
 * row read again under its lock is the committed one. The locks and the writes run in the
 * transaction of the use case.
 */
public interface RegistrationRepository extends LocalRegistrationRepository {
    /** Takes the row lock of a registration and returns it, or {@code null}. */
    Pending lockById(UUID id);

    /** The pending registration of an email, or {@code null}. */
    Pending byEmail(String email);

    /**
     * Expires the pending registration of this email past its life, which would keep a new one from
     * being inserted.
     */
    void expireConflicts(String email, Instant now);

    void insert(
            UUID id,
            String email,
            String passwordHash,
            String confirmationKeyHash,
            Instant expiresAt);

    /** A new request for the same email: the latest credentials and the latest page win. */
    void restart(UUID id, String passwordHash, String confirmationKeyHash);

    void replaceVerification(UUID registrationId);

    void addVerification(
            UUID id,
            UUID registrationId,
            String hash,
            String keyId,
            Instant now,
            Instant expiresAt);

    void extend(UUID registrationId, Instant expiresAt);

    /** The codes of a registration, the latest first. */
    List<Verification> verifications(UUID registrationId);

    /**
     * Counts a failed attempt on a code, and on the registration. Returns the failures of the
     * registration so far.
     */
    int recordFailure(UUID verificationId, UUID registrationId);

    /** No new code until {@code until}, and the current code is spent. */
    void blockCodes(UUID verificationId, UUID registrationId, Instant until, int attempts);

    /** The code with this id, or {@code null}. */
    Verification verification(UUID id);

    void consume(UUID operation, UUID registration, long accountId);

    record Pending(
            UUID id,
            String emailKey,
            String passwordHash,
            String confirmationKeyHash,
            int codeFailures,
            Instant codesBlockedUntil,
            Instant expiresAt,
            String status) {
        @Override
        public String toString() {
            return "Pending[redacted]";
        }
    }

    record Verification(
            UUID id,
            UUID registrationId,
            String tokenHash,
            String keyId,
            Instant expiresAt,
            String status,
            int failedAttempts) {
        @Override
        public String toString() {
            return "Verification[redacted]";
        }
    }
}
