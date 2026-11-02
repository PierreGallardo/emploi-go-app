package fr.emploigo.auth.application.registration;

import fr.emploigo.auth.application.password.PasswordHasher;
import fr.emploigo.auth.application.quota.QuotaEnforcer;
import fr.emploigo.auth.application.quota.QuotaScope;
import fr.emploigo.auth.domain.AccountIdentifier;
import fr.emploigo.auth.domain.RegistrationPassword;
import fr.emploigo.config.ApiOnly;

import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Asks for a registration: keeps its credentials pending under a new code, and sends that code. An
 * email that has an account keeps nothing and gets the notice of its account.
 */
@Service
@ApiOnly
public class StartRegistrationUseCase {
    private final RegistrationTransactions transactions;
    private final QuotaEnforcer quota;
    private final PasswordHasher hashing;
    private final RegistrationGatekeeper gatekeeper;
    private final ConfirmationAttemptLimiter limiter;
    private final RegistrationCodeMailer mailer;

    public StartRegistrationUseCase(
            RegistrationTransactions transactions,
            QuotaEnforcer quota,
            PasswordHasher hashing,
            RegistrationGatekeeper gatekeeper,
            ConfirmationAttemptLimiter limiter,
            RegistrationCodeMailer mailer) {
        this.transactions = transactions;
        this.quota = quota;
        this.hashing = hashing;
        this.gatekeeper = gatekeeper;
        this.limiter = limiter;
        this.mailer = mailer;
    }

    /**
     * @return the wait before the email may get another message
     */
    public Duration start(
            String email,
            String password,
            String confirmation,
            String confirmationKey,
            String address) {
        String emailKey;
        try {
            emailKey = AccountIdentifier.emailKey(email);
        } catch (IllegalArgumentException error) {
            throw new RegistrationFailedException(RegistrationFailedException.Reason.INVALID_INPUT);
        }
        // A password refused by the rule must not use the address's mail cooldown either.
        if (!password.equals(confirmation))
            throw new RegistrationFailedException(RegistrationFailedException.Reason.INVALID_INPUT);
        if (!RegistrationPassword.meetsRule(password))
            throw new RegistrationFailedException(RegistrationFailedException.Reason.WEAK_PASSWORD);
        // Before the quota, unlike the login, so a refused request does not use the address's mail
        // cooldown; this is a snapshot, since encode can still refuse later.
        hashing.requireCapacity();
        gatekeeper.requireMail();
        quota.check(QuotaScope.REGISTRATION_MAIL, emailKey, address);
        var mail = transactions.start(emailKey, hashing.encode(password), confirmationKey);
        if (RegistrationTransactions.keptNoRegistration(mail))
            limiter.takeOver(emailKey, confirmationKey);
        limiter.codeIssued(emailKey, confirmationKey);
        // Sent after the transaction that keeps the registration, never inside it.
        mailer.send(mail);
        return quota.sendDelay(QuotaScope.REGISTRATION_MAIL);
    }
}
