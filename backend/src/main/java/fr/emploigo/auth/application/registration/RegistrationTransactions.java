package fr.emploigo.auth.application.registration;

import fr.emploigo.auth.application.account.AccountRepository;
import fr.emploigo.auth.application.confirmation.ConfirmationTokens;
import fr.emploigo.auth.application.failure.AuthenticationFailedException;
import fr.emploigo.auth.application.google.GoogleRepository;
import fr.emploigo.auth.application.mail.AuthenticationMail;
import fr.emploigo.auth.application.mail.AuthenticationMailSettings;
import fr.emploigo.auth.application.session.AuthenticationSessions;
import fr.emploigo.auth.application.session.SessionPersistence;
import fr.emploigo.auth.domain.Account;
import fr.emploigo.auth.domain.AuthenticationSecrets;
import fr.emploigo.auth.domain.IdentifierTakenException;
import fr.emploigo.auth.domain.MailFlow;
import fr.emploigo.auth.domain.RegistrationCode;
import fr.emploigo.auth.domain.RegistrationPage;
import fr.emploigo.config.ApiOnly;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@ApiOnly
public class RegistrationTransactions {
    private final AccountRepository accounts;
    private final RegistrationRepository registrations;
    private final GoogleRepository google;
    private final AuthenticationMailSettings mail;
    private final ConfirmationTokens tokens;
    private final AuthenticationSessions sessions;
    private final Clock clock;

    public RegistrationTransactions(
            AccountRepository accounts,
            RegistrationRepository registrations,
            GoogleRepository google,
            AuthenticationMailSettings mail,
            ConfirmationTokens tokens,
            AuthenticationSessions sessions,
            Clock clock) {
        this.accounts = accounts;
        this.registrations = registrations;
        this.google = google;
        this.mail = mail;
        this.tokens = tokens;
        this.sessions = sessions;
        this.clock = clock;
    }

    /**
     * A new request for an email whose registration is pending replaces its credentials and its
     * page: the latest request wins, and a code only confirms the registration of the browser that
     * holds its key. An email that has an account keeps nothing: it gets the notice of the
     * account, and the caller has hashed the password all the same.
     */
    @Transactional
    public AuthenticationMail start(String email, String passwordHash, String confirmationKey) {
        if (accounts.existsByEmailKey(email)) return notice(email);
        Instant now = clock.instant();
        registrations.expireConflicts(email, now);
        String keyHash = AuthenticationSecrets.hash(confirmationKey);
        var existing = registrations.byEmail(email);
        // The purge of the worker takes no lock of the email: read the row again under its lock.
        if (existing != null) {
            existing = registrations.lockById(existing.id());
            if (existing != null
                    && (!existing.status().equals("PENDING") || !existing.expiresAt().isAfter(now)))
                existing = null;
        }
        UUID id;
        if (existing == null) {
            id = UUID.randomUUID();
            registrations.insert(
                    id, email, passwordHash, keyHash, now.plus(RegistrationPage.LIFETIME));
        } else {
            id = existing.id();
            // No new code during the hour: a signup always comes with a new key, so it gets what
            // an email with no pending registration gets.
            if (blocked(existing, now)) return notice(email);
            registrations.restart(id, passwordHash, keyHash);
            registrations.extend(id, now.plus(RegistrationPage.LIFETIME));
            registrations.replaceVerification(id);
        }
        return newCode(id, email);
    }

    /**
     * A new code. The hour without codes after 15 failures stops the page of the registration; any
     * other page gets the same answer as for an email with no registration.
     */
    @Transactional
    public AuthenticationMail resend(String email, String confirmationKey) {
        // Google may have created the account of the email while its registration was pending:
        // that registration can no longer be confirmed, so nothing extends it.
        if (accounts.existsByEmailKey(email)) return notice(email);
        var initial = registrations.byEmail(email);
        var pending = initial == null ? null : registrations.lockById(initial.id());
        Instant now = clock.instant();
        if (pending == null
                || !pending.status().equals("PENDING")
                || !pending.expiresAt().isAfter(now)) return notice(email);
        if (blocked(pending, now)) {
            if (holds(pending, confirmationKey)) throw waiting(pending, now);
            return notice(email);
        }
        registrations.extend(pending.id(), now.plus(RegistrationPage.LIFETIME));
        registrations.replaceVerification(pending.id());
        return newCode(pending.id(), email);
    }

    private static boolean holds(RegistrationRepository.Pending pending, String confirmationKey) {
        return pending.confirmationKeyHash() != null
                && AuthenticationSecrets.matches(confirmationKey, pending.confirmationKeyHash());
    }

    private static boolean blocked(RegistrationRepository.Pending pending, Instant now) {
        return pending.codesBlockedUntil() != null && pending.codesBlockedUntil().isAfter(now);
    }

    private static AuthenticationFailedException waiting(
            RegistrationRepository.Pending pending, Instant now) {
        return AuthenticationFailedException.codesBlocked(
                Duration.between(now, pending.codesBlockedUntil()));
    }

    /** Whether the mail is the notice of an account, for which a signup keeps no registration. */
    static boolean keptNoRegistration(AuthenticationMail mail) {
        return mail.kind().startsWith("ACCOUNT_");
    }

    /** The notice of the account of the email, or the general one when it has none. */
    private AuthenticationMail notice(String email) {
        return AuthenticationMail.of(
                MailFlow.CONFIRMATION,
                noticeKind(email),
                null,
                email,
                mail.sender(),
                mail.origin(),
                tokens.keyId());
    }

    private String noticeKind(String email) {
        var account = accounts.findByEmailKey(email).orElse(null);
        if (account == null) return "NOTICE";
        boolean linked = google.accountSubject(account.id()) != null;
        if (linked && account.local()) return "ACCOUNT_BOTH";
        return linked ? "ACCOUNT_GOOGLE" : "ACCOUNT_PASSWORD";
    }

    private AuthenticationMail newCode(UUID registration, String email) {
        UUID operation = UUID.randomUUID();
        Instant now = clock.instant();
        registrations.addVerification(
                operation,
                registration,
                AuthenticationSecrets.hash(tokens.codeCheck(operation, tokens.code(operation))),
                tokens.keyId(),
                now,
                now.plus(RegistrationCode.VALIDITY));
        return AuthenticationMail.of(
                MailFlow.CONFIRMATION,
                "CONFIRMATION",
                operation,
                email,
                mail.sender(),
                mail.origin(),
                tokens.keyId());
    }

    /**
     * Confirms the registration of the page that holds {@code confirmationKey}. A wrong code is
     * counted on the code and on the registration, and the transaction commits the count: the
     * refusals are returned. A page that does not hold the key changes nothing and learns nothing.
     */
    @Transactional
    ConfirmRegistrationResult confirm(
            String email,
            String code,
            String confirmationKey,
            SessionPersistence persistence,
            String previousCookie,
            UUID previousFamily) {
        var initial = registrations.byEmail(email);
        if (initial == null) return ConfirmRegistrationResult.ofForeignPage();
        // The row lock serializes the confirmations of this registration with its resend and with
        // the new requests for its email.
        var pending = registrations.lockById(initial.id());
        Instant now = clock.instant();
        if (pending == null
                || pending.confirmationKeyHash() == null
                || !AuthenticationSecrets.matches(confirmationKey, pending.confirmationKeyHash()))
            return ConfirmRegistrationResult.ofForeignPage();
        if (pending.status().equals("CONFIRMED"))
            return ConfirmRegistrationResult.refused(RegistrationFailedException.Reason.CODE_USED);
        if (!pending.status().equals("PENDING") || !pending.expiresAt().isAfter(now))
            return ConfirmRegistrationResult.ofForeignPage();
        if (blocked(pending, now))
            return ConfirmRegistrationResult.blocked(
                    Duration.between(now, pending.codesBlockedUntil()));
        var codes = registrations.verifications(pending.id());
        var current =
                codes.stream()
                        .filter(verification -> verification.status().equals("PENDING"))
                        .findFirst()
                        .orElse(null);
        if (current == null
                || !current.keyId().equals(tokens.keyId())
                || !current.expiresAt().isAfter(now))
            return ConfirmRegistrationResult.refused(
                    RegistrationFailedException.Reason.CODE_EXPIRED);
        if (current.failedAttempts() >= RegistrationCode.MAX_ATTEMPTS)
            return ConfirmRegistrationResult.refused(
                    RegistrationFailedException.Reason.CODE_ATTEMPTS_EXCEEDED);
        if (!matches(current, code)) return fail(pending, current, codes, code, now);
        // Google may have created the verified owner's account while this local registration was
        // pending.
        if (accounts.existsByEmailKey(pending.emailKey()))
            throw failure(RegistrationFailedException.Reason.ACCOUNT_ALREADY_EXISTS);
        long accountId;
        try {
            accountId =
                    accounts.save(
                                    Account.withoutUsername(
                                            pending.emailKey(), pending.passwordHash()))
                            .id();
        } catch (IdentifierTakenException error) {
            throw failure(refusalOf(error));
        }
        accounts.recordTermsAcceptance(accountId, now);
        registrations.consume(current.id(), pending.id(), accountId);
        return ConfirmRegistrationResult.granted(
                sessions.create(accountId, persistence, previousCookie, previousFamily));
    }

    private ConfirmRegistrationResult fail(
            RegistrationRepository.Pending pending,
            RegistrationRepository.Verification current,
            List<RegistrationRepository.Verification> codes,
            String code,
            Instant now) {
        boolean replaced =
                codes.stream().anyMatch(other -> other != current && matches(other, code));
        int failures = registrations.recordFailure(current.id(), pending.id());
        if (failures % RegistrationCode.FAILURES_PER_BLOCK == 0) {
            registrations.blockCodes(
                    current.id(),
                    pending.id(),
                    now.plus(RegistrationCode.BLOCK),
                    RegistrationCode.MAX_ATTEMPTS);
            return ConfirmRegistrationResult.blocked(RegistrationCode.BLOCK);
        }
        if (current.failedAttempts() + 1 >= RegistrationCode.MAX_ATTEMPTS)
            return ConfirmRegistrationResult.refused(
                    RegistrationFailedException.Reason.CODE_ATTEMPTS_EXCEEDED);
        return ConfirmRegistrationResult.refused(
                replaced
                        ? RegistrationFailedException.Reason.CODE_REPLACED
                        : RegistrationFailedException.Reason.INVALID_CODE);
    }

    private boolean matches(RegistrationRepository.Verification verification, String code) {
        return AuthenticationSecrets.matches(
                tokens.codeCheck(verification.id(), code), verification.tokenHash());
    }

    private static RegistrationFailedException failure(RegistrationFailedException.Reason reason) {
        return new RegistrationFailedException(reason);
    }

    /** The refusal of an identifier that another request committed after the checks. */
    private static RegistrationFailedException.Reason refusalOf(IdentifierTakenException error) {
        return switch (error.identifier()) {
            case EMAIL -> RegistrationFailedException.Reason.ACCOUNT_ALREADY_EXISTS;
            case USERNAME, GOOGLE_SUBJECT, GOOGLE_ACCOUNT -> throw error;
        };
    }
}
