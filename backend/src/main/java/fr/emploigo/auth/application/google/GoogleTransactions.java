package fr.emploigo.auth.application.google;

import fr.emploigo.auth.application.account.AccountRepository;
import fr.emploigo.auth.application.confirmation.ConfirmationTokens;
import fr.emploigo.auth.application.failure.AuthenticationFailedException;
import fr.emploigo.auth.application.mail.AuthenticationMail;
import fr.emploigo.auth.application.mail.AuthenticationMailSettings;
import fr.emploigo.auth.application.session.AuthenticationGrant;
import fr.emploigo.auth.application.session.AuthenticationSessions;
import fr.emploigo.auth.application.session.AuthenticationToken;
import fr.emploigo.auth.application.session.Credentials;
import fr.emploigo.auth.application.session.ReconnectionRepository;
import fr.emploigo.auth.application.session.SessionPersistence;
import fr.emploigo.auth.domain.Account;
import fr.emploigo.auth.domain.AccountIdentifier;
import fr.emploigo.auth.domain.AuthenticationSecrets;
import fr.emploigo.auth.domain.GoogleIdentity;
import fr.emploigo.auth.domain.MailFlow;
import fr.emploigo.auth.domain.SessionIdentity;
import fr.emploigo.config.ApiOnly;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Service
@ApiOnly
public class GoogleTransactions {
    public static final Duration PROOF_DURATION = Duration.ofMinutes(10);

    /** Always remembered: a Google session left open on a shared computer signs in anyway. */
    private static final SessionPersistence PERSISTENCE = SessionPersistence.REMEMBERED;
    private final GoogleRepository google;
    private final AccountRepository accounts;
    private final LocalRegistrationRepository registrations;
    private final ReconnectionRepository reconnections;
    private final AuthenticationSessions sessions;
    private final AuthenticationMailSettings mail;
    private final ConfirmationTokens tokens;
    private final Clock clock;

    public GoogleTransactions(
            GoogleRepository google,
            AccountRepository accounts,
            LocalRegistrationRepository registrations,
            ReconnectionRepository reconnections,
            AuthenticationSessions sessions,
            AuthenticationMailSettings mail,
            ConfirmationTokens tokens,
            Clock clock) {
        this.google = google;
        this.accounts = accounts;
        this.registrations = registrations;
        this.reconnections = reconnections;
        this.sessions = sessions;
        this.mail = mail;
        this.tokens = tokens;
        this.clock = clock;
    }

    @Transactional
    public void start(String state, String proof, SessionIdentity identity) {
        google.start(
                AuthenticationSecrets.hash(state),
                AuthenticationSecrets.hash(proof),
                identity,
                clock.instant().plus(PROOF_DURATION));
    }

    // Consumption commits before the external exchange, even when Google rejects it afterwards.
    @Transactional
    public GoogleRepository.Scope consume(String state, String proof, SessionIdentity identity) {
        var scope =
                google.consume(
                        AuthenticationSecrets.hash(state),
                        AuthenticationSecrets.hash(proof),
                        clock.instant());
        if (scope == null || !scope.matches(identity) || !active(scope, clock.instant()))
            throw failure(GoogleAuthenticationFailedException.Reason.INVALID_FLOW);
        return scope;
    }

    public record Callback(AuthenticationGrant grant, UUID pendingId, String pendingProof) {
        @Override
        public String toString() {
            return "Callback[redacted]";
        }
    }

    @Transactional
    public Callback accept(
            GoogleIdentity identity,
            GoogleRepository.Scope scope,
            SessionIdentity current,
            String state,
            String previousCookie,
            UUID previousFamily) {
        String flowHash = AuthenticationSecrets.hash(state);
        lockCurrentFlow(flowHash);
        if (!scope.matches(current) || !active(scope, clock.instant()))
            throw failure(GoogleAuthenticationFailedException.Reason.INVALID_FLOW);
        Long linked = google.linkedAccount(identity.subject());
        if (linked != null) {
            if (scope.accountId() != null && !scope.accountId().equals(linked))
                throw failure(GoogleAuthenticationFailedException.Reason.ALREADY_LINKED);
            var owner = accounts.lockByIdForUpdate(linked).orElse(null);
            if (!active(scope, clock.instant()))
                throw failure(GoogleAuthenticationFailedException.Reason.INVALID_FLOW);
            if (owner == null || !owner.mayAuthenticate())
                throw failure(GoogleAuthenticationFailedException.Reason.PROVIDER_REJECTED);
            var grant =
                    sessions.create(
                            new Credentials(owner.id(), owner.credentialVersion()),
                            PERSISTENCE,
                            previousCookie,
                            previousFamily);
            google.issued(flowHash, grant.account().familyId());
            return new Callback(grant, null, null);
        }
        if (scope.accountId() != null) {
            accounts.lockByIdForUpdate(scope.accountId());
            if (!active(scope, clock.instant()))
                throw failure(GoogleAuthenticationFailedException.Reason.INVALID_FLOW);
        }
        UUID id = UUID.randomUUID();
        String proof = AuthenticationSecrets.generate();
        google.pending(
                id,
                AuthenticationSecrets.hash(proof),
                flowHash,
                identity,
                scope,
                clock.instant().plus(PROOF_DURATION));
        return new Callback(null, id, proof);
    }

    public enum Stage {
        EMAIL_CONFIRMATION_REQUIRED,
        ACCOUNT_ACCESS_REQUIRED,
        CHOOSE_USERNAME,
        CONFIRM_LINK
    }

    @Transactional(readOnly = true)
    public String mailIdentity(UUID id, String proof, SessionIdentity current) {
        return pending(id, proof, current).identity().email();
    }

    @Transactional(readOnly = true)
    public Stage stage(UUID id, String proof, SessionIdentity current) {
        var pending = pending(id, proof, current);
        if (!google.emailConfirmed(pending.identity(), clock.instant()))
            return Stage.EMAIL_CONFIRMATION_REQUIRED;
        if (pending.accessVerified()) return Stage.CONFIRM_LINK;
        return pending.scope().accountId() != null
                        || accounts.existsByEmailKey(pending.identity().email())
                ? Stage.ACCOUNT_ACCESS_REQUIRED
                : Stage.CHOOSE_USERNAME;
    }

    @Transactional
    public AuthenticationGrant access(
            UUID id,
            String proof,
            SessionIdentity current,
            Credentials credentials,
            String previousCookie,
            UUID previousFamily) {
        var initial = pending(id, proof, current);
        lockCurrentFlow(initial.flowHash());
        var owner = accounts.lockByIdForUpdate(credentials.accountId()).orElse(null);
        var pending = pending(id, proof, current);
        requireEmail(pending);
        // A second access would open a family that no longer is the issued one of the flow.
        if (pending.accessVerified())
            throw failure(GoogleAuthenticationFailedException.Reason.INVALID_FLOW);
        if (owner == null
                || !owner.mayLogInWithPassword()
                || owner.credentialVersion() != credentials.version()
                || !owner.emailKey().equals(pending.identity().email())
                || pending.scope().accountId() != null
                        && !pending.scope().accountId().equals(owner.id()))
            throw AuthenticationFailedException.invalidCredentials();
        if (google.linkedAccount(pending.identity().subject()) != null
                || google.accountSubject(owner.id()) != null)
            throw failure(GoogleAuthenticationFailedException.Reason.ALREADY_LINKED);
        var grant = sessions.create(credentials, PERSISTENCE, previousCookie, previousFamily);
        google.access(
                id,
                grant.account().id(),
                grant.account().credentialVersion(),
                grant.account().familyId());
        google.issued(pending.flowHash(), grant.account().familyId());
        return grant;
    }

    @Transactional
    public AuthenticationGrant signup(
            UUID id,
            String proof,
            SessionIdentity current,
            String username,
            String previousCookie,
            UUID previousFamily) {
        var initial = pending(id, proof, current);
        lockCurrentFlow(initial.flowHash());
        String normalized;
        try {
            normalized = AccountIdentifier.username(username);
        } catch (IllegalArgumentException error) {
            throw failure(GoogleAuthenticationFailedException.Reason.INVALID_USERNAME);
        }
        var pending = pending(id, proof, current);
        requireEmail(pending);
        if (pending.scope().accountId() != null
                || accounts.existsByEmailKey(pending.identity().email()))
            throw failure(GoogleAuthenticationFailedException.Reason.ACCOUNT_ACCESS_REQUIRED);
        if (google.linkedAccount(pending.identity().subject()) != null)
            throw failure(GoogleAuthenticationFailedException.Reason.ALREADY_LINKED);
        if (accounts.existsByUsername(normalized))
            throw failure(GoogleAuthenticationFailedException.Reason.USERNAME_UNAVAILABLE);
        // Before the insert: a local confirmation takes the row, then the email, in that order.
        registrations.expirePending(pending.identity().email());
        long accountId =
                accounts.save(
                                Account.confirmed(
                                        normalized, pending.identity().email(), null))
                        .id();
        accounts.recordTermsAcceptance(accountId, clock.instant());
        google.link(pending.identity().subject(), accountId);
        google.finish(id, clock.instant());
        var grant =
                sessions.create(
                        new Credentials(accountId, 0), PERSISTENCE, previousCookie, previousFamily);
        google.issued(pending.flowHash(), grant.account().familyId());
        return grant;
    }

    /** Links the account, and decides the notice that tells its owner. */
    @Transactional
    public AuthenticationMail link(UUID id, String proof, SessionIdentity current) {
        var initial = pending(id, proof, current);
        lockCurrentFlow(initial.flowHash());
        var owner =
                current == null
                        ? null
                        : accounts.lockByIdForUpdate(current.accountId()).orElse(null);
        var pending = pending(id, proof, current);
        requireEmail(pending);
        if (!pending.accessVerified()
                || current == null
                || !current.explicit()
                || !current.startedAt().plus(Duration.ofMinutes(5)).isAfter(clock.instant())
                || owner == null
                || !owner.mayLogInWithPassword()
                || owner.credentialVersion() != current.credentialVersion())
            throw failure(GoogleAuthenticationFailedException.Reason.ACCOUNT_ACCESS_REQUIRED);
        if (google.linkedAccount(pending.identity().subject()) != null
                || google.accountSubject(owner.id()) != null)
            throw failure(GoogleAuthenticationFailedException.Reason.ALREADY_LINKED);
        google.link(pending.identity().subject(), owner.id());
        google.finish(id, clock.instant());
        return AuthenticationMail.of(
                MailFlow.GOOGLE,
                "LINKED",
                null,
                owner.emailKey(),
                mail.sender(),
                mail.origin(),
                tokens.keyId());
    }

    /** Decides the email that confirms an external Google address, and returns it. */
    @Transactional
    public AuthenticationMail requestEmail(UUID id, String proof, SessionIdentity current) {
        // No lock of the browser: the link only confirms the address of this Google identity, and
        // the unique pending verification of the identity arbitrates two requests.
        var pending = pending(id, proof, current);
        UUID operation = UUID.randomUUID();
        if (!google.prepareVerification(
                operation,
                pending.identity(),
                AuthenticationSecrets.hash(tokens.googleToken(operation)),
                tokens.keyId(),
                clock.instant().plus(Duration.ofHours(24))))
            throw failure(GoogleAuthenticationFailedException.Reason.UNAVAILABLE);
        return AuthenticationMail.of(
                MailFlow.GOOGLE,
                "VERIFY",
                operation,
                pending.identity().email(),
                mail.sender(),
                mail.origin(),
                tokens.keyId());
    }

    /**
     * Uses the link once. The update only passes a link that is still pending and unexpired, so
     * two confirmations at the same time consume it once.
     */
    @Transactional
    void confirmEmail(AuthenticationToken link) {
        var operation = verified(link);
        if (operation.status().equals("REPLACED") || !operation.keyId().equals(tokens.keyId()))
            throw failure(GoogleAuthenticationFailedException.Reason.REPLACED_LINK);
        if (operation.status().equals("USED")) throw failure(GoogleAuthenticationFailedException.Reason.USED_LINK);
        if (!operation.expiresAt().isAfter(clock.instant()))
            throw failure(GoogleAuthenticationFailedException.Reason.EXPIRED_LINK);
        if (!google.confirm(operation.id(), clock.instant()))
            throw failure(refusedLink(operation.id()));
    }

    /** The state that a concurrent confirmation or replacement left the link in. */
    private GoogleAuthenticationFailedException.Reason refusedLink(UUID id) {
        var current = google.verification(id);
        if (current == null) return GoogleAuthenticationFailedException.Reason.EXPIRED_LINK;
        return switch (current.status()) {
            case "USED" -> GoogleAuthenticationFailedException.Reason.USED_LINK;
            case "REPLACED" -> GoogleAuthenticationFailedException.Reason.REPLACED_LINK;
            default -> GoogleAuthenticationFailedException.Reason.EXPIRED_LINK;
        };
    }

    /**
     * The account of the flow may still authenticate, with the version the flow was started for,
     * and its reconnection family is alive. A flow without an account is always active.
     */
    private boolean active(GoogleRepository.Scope scope, Instant now) {
        if (scope.accountId() == null) return true;
        return accounts.findById(scope.accountId())
                        .filter(
                                account ->
                                        account.mayAuthenticate()
                                                && account.credentialVersion() == scope.version())
                        .isPresent()
                && reconnections.active(scope.family(), scope.accountId(), scope.version(), now);
    }

    private GoogleRepository.Pending pending(UUID id, String proof, SessionIdentity current) {
        var pending = id == null || proof == null ? null : google.pending(id);
        if (pending == null
                || !AuthenticationSecrets.matches(proof, pending.browserHash())
                || !pending.scope().matches(current)
                || !google.currentFlow(pending.flowHash())
                || !active(pending.scope(), clock.instant())
                || pending.consumedAt() != null) throw failure(GoogleAuthenticationFailedException.Reason.INVALID_FLOW);
        if (!pending.expiresAt().isAfter(clock.instant()))
            throw failure(GoogleAuthenticationFailedException.Reason.EXPIRED_FLOW);
        return pending;
    }

    private void requireEmail(GoogleRepository.Pending pending) {
        if (!google.emailConfirmed(pending.identity(), clock.instant()))
            throw failure(GoogleAuthenticationFailedException.Reason.EMAIL_CONFIRMATION_REQUIRED);
    }

    private GoogleRepository.Verification verified(AuthenticationToken link) {
        var operation = google.verification(link.id());
        if (operation == null || !AuthenticationSecrets.matches(link.secret(), operation.hash()))
            throw failure(GoogleAuthenticationFailedException.Reason.INVALID_LINK);
        return operation;
    }

    private void lockCurrentFlow(String flowHash) {
        if (!google.lockCurrentFlow(flowHash)) throw failure(GoogleAuthenticationFailedException.Reason.INVALID_FLOW);
    }

    private static GoogleAuthenticationFailedException failure(GoogleAuthenticationFailedException.Reason reason) {
        return new GoogleAuthenticationFailedException(reason);
    }
}
