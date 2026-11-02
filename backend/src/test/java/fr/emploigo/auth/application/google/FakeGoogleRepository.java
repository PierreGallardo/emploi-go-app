package fr.emploigo.auth.application.google;

import fr.emploigo.auth.domain.GoogleIdentity;
import fr.emploigo.auth.domain.IdentifierTakenException;
import fr.emploigo.auth.domain.IdentifierTakenException.Identifier;
import fr.emploigo.auth.domain.SessionIdentity;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The Google flows in memory, with the rules of the tables: a new flow of a browser supersedes the
 * others, and a Google subject is linked to one account at most. The locks change nothing.
 */
public final class FakeGoogleRepository implements GoogleRepository {
    private final Map<String, Flow> flows = new HashMap<>();
    private final Map<UUID, Pending> pendings = new HashMap<>();
    private final Map<UUID, Verification> verifications = new HashMap<>();
    private final Map<String, Long> links = new HashMap<>();
    private Identifier conflictOnNextLink;
    private boolean conflictOnNextVerification;

    /**
     * Makes the next link refuse {@code identifier} as if another transaction had committed it
     * after the checks of the use case: nothing is linked.
     */
    /** Makes the next verification lose to one that another request inserted at the same time. */
    public void conflictOnNextVerification() {
        conflictOnNextVerification = true;
    }

    public void conflictOnNextLink(Identifier identifier) {
        conflictOnNextLink = identifier;
    }

    @Override
    public void start(
            String stateHash, String browserHash, SessionIdentity identity, Instant expiry) {
        flows.values().stream()
                .filter(flow -> flow.browserHash.equals(browserHash))
                .forEach(Flow::supersede);
        Scope scope =
                identity == null
                        ? new Scope(null, null, null)
                        : new Scope(
                                identity.accountId(),
                                identity.credentialVersion(),
                                identity.familyId());
        flows.put(stateHash, new Flow(browserHash, scope, expiry));
    }

    @Override
    public Scope consume(String stateHash, String browserHash, Instant now) {
        Flow flow = flows.get(stateHash);
        if (flow == null
                || !flow.browserHash.equals(browserHash)
                || flow.consumed
                || flow.superseded
                || !flow.expiresAt.isAfter(now)) return null;
        flow.consumed = true;
        return flow.scope;
    }

    @Override
    public void principalChanged(String browserHash, UUID retainedFamily) {
        throw new UnsupportedOperationException("Hors des cas d'usage testés : principalChanged");
    }

    @Override
    public void issued(String flowHash, UUID family) {
        Flow flow = flows.get(flowHash);
        if (flow != null) flow.issued = family;
    }

    @Override
    public boolean currentFlow(String hash) {
        Flow flow = flows.get(hash);
        return flow != null && flow.consumed && !flow.superseded;
    }

    @Override
    public boolean lockCurrentFlow(String hash) {
        return currentFlow(hash);
    }

    @Override
    public Long linkedAccount(String subject) {
        return links.get(subject);
    }

    @Override
    public String accountSubject(long accountId) {
        return links.entrySet().stream()
                .filter(link -> link.getValue() == accountId)
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(null);
    }

    @Override
    public void link(String subject, long accountId) {
        if (conflictOnNextLink != null) {
            var identifier = conflictOnNextLink;
            conflictOnNextLink = null;
            throw new IdentifierTakenException(identifier);
        }
        if (links.containsKey(subject))
            throw new IdentifierTakenException(Identifier.GOOGLE_SUBJECT);
        if (links.containsValue(accountId))
            throw new IdentifierTakenException(Identifier.GOOGLE_ACCOUNT);
        links.put(subject, accountId);
    }

    @Override
    public void pending(
            UUID id,
            String hash,
            String flowHash,
            GoogleIdentity identity,
            Scope scope,
            Instant expiry) {
        pendings.put(id, new Pending(id, hash, flowHash, identity, scope, false, expiry, null));
    }

    @Override
    public Pending pending(UUID id) {
        return pendings.get(id);
    }

    @Override
    public void access(UUID id, long accountId, long version, UUID family) {
        Pending pending = pendings.get(id);
        if (pending != null)
            pendings.put(
                    id,
                    new Pending(
                            id,
                            pending.browserHash(),
                            pending.flowHash(),
                            pending.identity(),
                            new Scope(accountId, version, family),
                            true,
                            pending.expiresAt(),
                            pending.consumedAt()));
    }

    @Override
    public void finish(UUID id, Instant now) {
        Pending pending = pendings.get(id);
        if (pending != null)
            pendings.put(
                    id,
                    new Pending(
                            id,
                            pending.browserHash(),
                            pending.flowHash(),
                            pending.identity(),
                            pending.scope(),
                            pending.accessVerified(),
                            pending.expiresAt(),
                            now));
    }

    @Override
    public boolean emailConfirmed(GoogleIdentity identity, Instant now) {
        return identity.authoritativeEmail()
                || verifications.values().stream()
                        .anyMatch(
                                verification ->
                                        verification.subject().equals(identity.subject())
                                                && verification.email().equals(identity.email())
                                                && verification.status().equals("USED")
                                                && verification.expiresAt().isAfter(now));
    }

    @Override
    public Verification verification(UUID id) {
        return verifications.get(id);
    }

    @Override
    public boolean prepareVerification(
            UUID id, GoogleIdentity identity, String hash, String keyId, Instant expiry) {
        if (conflictOnNextVerification) {
            conflictOnNextVerification = false;
            return false;
        }
        verifications.replaceAll(
                (key, verification) ->
                        verification.subject().equals(identity.subject())
                                        && verification.email().equals(identity.email())
                                        && verification.status().equals("PENDING")
                                ? withStatus(verification, "REPLACED")
                                : verification);
        verifications.put(
                id,
                new Verification(
                        id,
                        identity.subject(),
                        identity.email(),
                        hash,
                        keyId,
                        "PENDING",
                        expiry));
        return true;
    }

    @Override
    public boolean confirm(UUID id, Instant now) {
        Verification verification = verifications.get(id);
        if (verification == null
                || !verification.status().equals("PENDING")
                || !verification.expiresAt().isAfter(now)) return false;
        verifications.put(id, withStatus(verification, "USED"));
        return true;
    }

    private static Verification withStatus(Verification verification, String status) {
        return new Verification(
                verification.id(),
                verification.subject(),
                verification.email(),
                verification.hash(),
                verification.keyId(),
                status,
                verification.expiresAt());
    }

    private static final class Flow {
        private final String browserHash;
        private final Scope scope;
        private final Instant expiresAt;
        private boolean consumed;
        private boolean superseded;
        private UUID issued;

        Flow(String browserHash, Scope scope, Instant expiresAt) {
            this.browserHash = browserHash;
            this.scope = scope;
            this.expiresAt = expiresAt;
        }

        void supersede() {
            consumed = true;
            superseded = true;
        }
    }
}
