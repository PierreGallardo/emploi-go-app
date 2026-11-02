package fr.emploigo.auth.infrastructure.google;

import fr.emploigo.auth.application.google.GoogleRepository;
import fr.emploigo.auth.domain.AuthenticationSecrets;
import fr.emploigo.auth.domain.GoogleIdentity;
import fr.emploigo.auth.domain.IdentifierTakenException;
import fr.emploigo.auth.domain.IdentifierTakenException.Identifier;
import fr.emploigo.auth.domain.SessionIdentity;
import fr.emploigo.auth.infrastructure.persistence.UniqueViolations;
import fr.emploigo.config.ApiOnly;

import jakarta.persistence.EntityManager;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.UUID;

/**
 * The Google flows in PostgreSQL. Writes are the statements of the flows, with their clauses: an
 * insert through {@code save} would merge an assigned identifier into an existing row rather than
 * fail, which is why the inserts are native.
 */
@Repository
@ApiOnly
public class JpaGoogleRepository implements GoogleRepository {
    private static final String SUBJECT_CONSTRAINT = "auth_google_identities_pkey";
    private static final String ACCOUNT_CONSTRAINT = "auth_google_identities_account_id_key";
    private final SpringDataGoogleIdentityRepository identities;
    private final SpringDataGoogleFlowRepository flows;
    private final SpringDataGooglePendingRepository pendings;
    private final SpringDataGoogleVerificationRepository verifications;
    private final EntityManager entities;

    public JpaGoogleRepository(
            SpringDataGoogleIdentityRepository identities,
            SpringDataGoogleFlowRepository flows,
            SpringDataGooglePendingRepository pendings,
            SpringDataGoogleVerificationRepository verifications,
            EntityManager entities) {
        this.identities = identities;
        this.flows = flows;
        this.pendings = pendings;
        this.verifications = verifications;
        this.entities = entities;
    }

    @Override
    public void start(
            String stateHash, String browserHash, SessionIdentity identity, Instant expiry) {
        // Replaces other attempts from this browser; late session saves cannot revive them.
        lockBrowser(browserHash);
        flows.supersedeBrowser(browserHash);
        flows.insert(
                stateHash,
                browserHash,
                identity == null ? null : identity.accountId(),
                identity == null ? null : identity.credentialVersion(),
                identity == null ? null : identity.familyId(),
                expiry);
    }

    @Override
    public Scope consume(String stateHash, String browserHash, Instant now) {
        lockBrowser(browserHash);
        return flows.consume(stateHash, browserHash, now).stream()
                .findFirst()
                .map(
                        row ->
                                new Scope(
                                        row.getAccountId(),
                                        row.getCredentialVersion(),
                                        row.getFamilyId()))
                .orElse(null);
    }

    @Override
    public void principalChanged(String browserHash, UUID retainedFamily) {
        lockBrowser(browserHash);
        flows.supersedeBrowser(browserHash);
        // A grant committed before another login must not restore its principal through late
        // cookies.
        flows.revokeIssuedReconnections(browserHash, retainedFamily);
    }

    @Override
    public void issued(String flowHash, UUID family) {
        flows.issue(flowHash, family);
    }

    @Override
    public boolean currentFlow(String hash) {
        return flows.existsByStateHashAndConsumedTrueAndSupersededFalse(hash);
    }

    @Override
    public boolean lockCurrentFlow(String hash) {
        var browser =
                flows.findById(hash)
                        .map(this::detached)
                        .map(GoogleFlowJpaEntity::browserHash)
                        .orElse(null);
        if (browser == null) return false;
        lockBrowser(browser);
        return currentFlow(hash);
    }

    @Override
    public Long linkedAccount(String subject) {
        return identities
                .findById(subject)
                .map(this::detached)
                .map(GoogleIdentityJpaEntity::accountId)
                .orElse(null);
    }

    @Override
    public String accountSubject(long accountId) {
        return identities
                .findByAccountId(accountId)
                .map(this::detached)
                .map(GoogleIdentityJpaEntity::subject)
                .orElse(null);
    }

    @Override
    public void link(String subject, long accountId) {
        try {
            identities.insert(subject, accountId);
        } catch (DataIntegrityViolationException error) {
            throw takenIdentifier(error);
        }
    }

    @Override
    public void pending(
            UUID id,
            String hash,
            String flowHash,
            GoogleIdentity identity,
            Scope scope,
            Instant expiry) {
        pendings.insert(
                id,
                hash,
                flowHash,
                identity.subject(),
                identity.email(),
                identity.authoritativeEmail(),
                scope.accountId(),
                scope.version(),
                scope.family(),
                expiry);
    }

    @Override
    public Pending pending(UUID id) {
        var row = pendings.findById(id);
        return row.map(this::detached).map(GooglePendingJpaEntity::toPending).orElse(null);
    }

    @Override
    public void access(UUID id, long accountId, long version, UUID family) {
        pendings.access(id, accountId, version, family);
    }

    @Override
    public void finish(UUID id, Instant now) {
        pendings.finish(id, now);
    }

    @Override
    public boolean emailConfirmed(GoogleIdentity identity, Instant now) {
        return identity.authoritativeEmail()
                || verifications.existsBySubjectAndEmailKeyAndStatusAndExpiresAtAfter(
                        identity.subject(), identity.email(), "USED", now);
    }

    @Override
    public Verification verification(UUID id) {
        return verifications
                .findById(id)
                .map(this::detached)
                .map(GoogleVerificationJpaEntity::toVerification)
                .orElse(null);
    }

    @Override
    public boolean prepareVerification(
            UUID id, GoogleIdentity identity, String hash, String keyId, Instant expiry) {
        verifications.replacePending(identity.subject(), identity.email());
        int inserted =
                verifications.insert(id, identity.subject(), identity.email(), hash, keyId, expiry);
        return inserted == 1;
    }

    @Override
    public boolean confirm(UUID id, Instant now) {
        return verifications.confirm(id, now) == 1;
    }

    private void lockBrowser(String browserHash) {
        lock("google:browser:" + browserHash);
    }

    /** Takes the transaction lock of a flow browser, waiting at most 3 seconds. */
    private void lock(String scope) {
        flows.setLockTimeout();
        flows.lockKey(
                Long.parseUnsignedLong(AuthenticationSecrets.hash(scope).substring(0, 16), 16));
    }

    /**
     * Names the identity that the database found taken. Any other failure is passed on as it is.
     */
    private static RuntimeException takenIdentifier(DataIntegrityViolationException error) {
        return switch (UniqueViolations.violatedConstraint(error).orElse("")) {
            case SUBJECT_CONSTRAINT -> new IdentifierTakenException(Identifier.GOOGLE_SUBJECT);
            case ACCOUNT_CONSTRAINT -> new IdentifierTakenException(Identifier.GOOGLE_ACCOUNT);
            default -> error;
        };
    }

    /**
     * Detaches the row: a managed entity would stay in the persistence context, and a locked read
     * that follows would return that copy rather than the row as committed.
     */
    private <T> T detached(T row) {
        entities.detach(row);
        return row;
    }
}
