package fr.emploigo.auth.application.recovery;

import fr.emploigo.auth.application.account.AccountRepository;
import fr.emploigo.auth.application.confirmation.ConfirmationTokens;
import fr.emploigo.auth.application.mail.AuthenticationMail;
import fr.emploigo.auth.application.mail.AuthenticationMailSettings;
import fr.emploigo.auth.application.session.AuthenticationToken;
import fr.emploigo.auth.domain.AuthenticationSecrets;
import fr.emploigo.auth.domain.MailFlow;
import fr.emploigo.config.ApiOnly;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

@Service
@ApiOnly
public class RecoveryTransactions {
    public static final Duration LINK_DURATION = Duration.ofMinutes(30);
    private final AccountRepository accounts;
    private final PasswordRecoveryRepository recovery;
    private final AuthenticationMailSettings mail;
    private final ConfirmationTokens tokens;
    private final Clock clock;

    public RecoveryTransactions(
            AccountRepository accounts,
            PasswordRecoveryRepository recovery,
            AuthenticationMailSettings mail,
            ConfirmationTokens tokens,
            Clock clock) {
        this.accounts = accounts;
        this.recovery = recovery;
        this.mail = mail;
        this.tokens = tokens;
        this.clock = clock;
    }

    /** Decides the email of a recovery request, and returns it. */
    @Transactional
    public AuthenticationMail request(String email) {
        var owner = accounts.findByEmailKey(email).orElse(null);
        String kind = "NOTICE";
        UUID resetId = null;
        if (owner != null && owner.mayAuthenticate()) {
            if (owner.local()) {
                recovery.replace(owner.id());
                resetId = UUID.randomUUID();
                kind = "RESET";
                recovery.insert(
                        resetId,
                        owner.id(),
                        owner.credentialVersion(),
                        AuthenticationSecrets.hash(tokens.recoveryToken(resetId)),
                        tokens.keyId(),
                        clock.instant().plus(LINK_DURATION));
            } else kind = "GOOGLE";
        }
        // Every address takes the same provider path; only a verified local owner gets a recovery
        // capability.
        return AuthenticationMail.of(
                MailFlow.RECOVERY,
                kind,
                resetId,
                email,
                mail.sender(),
                mail.origin(),
                tokens.keyId());
    }

    /**
     * A password changed by a recovery. {@code emailKey} and {@code username} name the login
     * counters to reset. {@code mail} is the notice of the change.
     */
    public record PasswordChanged(AuthenticationMail mail, String emailKey, String username) {
        @Override
        public String toString() {
            return "PasswordChanged[redacted]";
        }
    }

    /**
     * Refuses a link that cannot be used any more, before the work of a new password: a used link
     * is answered as used, a replaced or expired one as such.
     */
    @Transactional(readOnly = true)
    void checkLink(AuthenticationToken link) {
        var reset = verified(link);
        if (reset.status().equals("USED"))
            throw failure(PasswordRecoveryFailedException.Reason.USED_LINK);
        validatePending(reset);
    }

    @Transactional
    PasswordChanged complete(AuthenticationToken link, String passwordHash) {
        var initial = verified(link);
        var owner = accounts.findById(initial.accountId()).orElse(null);
        if (owner == null) throw failure(PasswordRecoveryFailedException.Reason.INVALID_LINK);
        owner = accounts.lockByIdForUpdate(owner.id()).orElse(null);
        var reset = verified(link);
        if (reset.status().equals("USED"))
            throw failure(PasswordRecoveryFailedException.Reason.USED_LINK);
        validatePending(reset);
        if (owner == null
                || !owner.mayLogInWithPassword()
                || owner.credentialVersion() != reset.version())
            throw failure(PasswordRecoveryFailedException.Reason.REPLACED_LINK);
        // A link replaced or used since it was read is refused here, and the change is undone.
        if (!recovery.consume(reset, clock.instant())) throw refusedLink(link);
        accounts.changePassword(owner.id(), passwordHash);
        var notice =
                AuthenticationMail.of(
                        MailFlow.RECOVERY,
                        "CHANGED",
                        reset.id(),
                        owner.emailKey(),
                        mail.sender(),
                        mail.origin(),
                        tokens.keyId());
        return new PasswordChanged(notice, owner.emailKey(), owner.username());
    }

    private PasswordRecoveryFailedException refusedLink(AuthenticationToken link) {
        var reset = recovery.reset(link.id());
        if (reset == null) return failure(PasswordRecoveryFailedException.Reason.INVALID_LINK);
        return failure(
                switch (reset.status()) {
                    case "USED" -> PasswordRecoveryFailedException.Reason.USED_LINK;
                    case "REPLACED" -> PasswordRecoveryFailedException.Reason.REPLACED_LINK;
                    default -> PasswordRecoveryFailedException.Reason.EXPIRED_LINK;
                });
    }

    private void validatePending(PasswordRecoveryRepository.Reset reset) {
        if (reset.status().equals("REPLACED")) throw failure(PasswordRecoveryFailedException.Reason.REPLACED_LINK);
        if (!reset.expiresAt().isAfter(clock.instant()))
            throw failure(PasswordRecoveryFailedException.Reason.EXPIRED_LINK);
    }

    private PasswordRecoveryRepository.Reset verified(AuthenticationToken link) {
        var reset = recovery.reset(link.id());
        if (reset == null || !AuthenticationSecrets.matches(link.secret(), reset.hash()))
            throw failure(PasswordRecoveryFailedException.Reason.INVALID_LINK);
        if (!reset.keyId().equals(tokens.keyId()))
            throw failure(PasswordRecoveryFailedException.Reason.REPLACED_LINK);
        return reset;
    }

    private static PasswordRecoveryFailedException failure(PasswordRecoveryFailedException.Reason reason) {
        return new PasswordRecoveryFailedException(reason);
    }
}
