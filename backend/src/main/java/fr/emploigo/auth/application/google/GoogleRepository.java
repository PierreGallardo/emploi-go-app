package fr.emploigo.auth.application.google;

import fr.emploigo.auth.application.session.BrowserProofRepository;
import fr.emploigo.auth.domain.GoogleIdentity;
import fr.emploigo.auth.domain.SessionIdentity;

import java.time.Instant;
import java.util.UUID;

/** The Google flows, their pending links and their email verifications, kept durably. */
public interface GoogleRepository extends BrowserProofRepository {
    record Scope(Long accountId, Long version, UUID family) {
        public boolean matches(SessionIdentity identity) {
            return accountId == null
                    ? identity == null
                    : identity != null
                            && accountId == identity.accountId()
                            && version == identity.credentialVersion()
                            && family.equals(identity.familyId());
        }

        @Override
        public String toString() {
            return "Scope[redacted]";
        }
    }

    record Pending(
            UUID id,
            String browserHash,
            String flowHash,
            GoogleIdentity identity,
            Scope scope,
            boolean accessVerified,
            Instant expiresAt,
            Instant consumedAt) {
        @Override
        public String toString() {
            return "Pending[redacted]";
        }
    }

    record Verification(
            UUID id,
            String subject,
            String email,
            String hash,
            String keyId,
            String status,
            Instant expiresAt) {
        @Override
        public String toString() {
            return "Verification[redacted]";
        }
    }

    /** Replaces the other flows of this browser: a late session save cannot revive them. */
    void start(String stateHash, String browserHash, SessionIdentity identity, Instant expiry);

    Scope consume(String stateHash, String browserHash, Instant now);

    void issued(String flowHash, UUID family);

    boolean currentFlow(String hash);

    /** Locks the browser of the flow and says whether the flow is still the current one. */
    boolean lockCurrentFlow(String hash);

    Long linkedAccount(String subject);

    String accountSubject(long accountId);

    void link(String subject, long accountId);

    void pending(
            UUID id,
            String hash,
            String flowHash,
            GoogleIdentity identity,
            Scope scope,
            Instant expiry);

    Pending pending(UUID id);

    void access(UUID id, long accountId, long version, UUID family);

    void finish(UUID id, Instant now);

    boolean emailConfirmed(GoogleIdentity identity, Instant now);

    Verification verification(UUID id);

    /**
     * Stores the pending verification of the address, which replaces the earlier one. Says false
     * when another request stored one for the same address at the same time: nothing is stored.
     */
    boolean prepareVerification(
            UUID id, GoogleIdentity identity, String hash, String keyId, Instant expiry);

    /**
     * Marks the verification used, if it is still pending and not expired at {@code now}. Says
     * whether it did: a link confirmed, replaced or expired meanwhile is not used again.
     */
    boolean confirm(UUID id, Instant now);
}
