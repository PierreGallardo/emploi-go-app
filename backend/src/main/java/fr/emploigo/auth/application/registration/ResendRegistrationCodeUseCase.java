package fr.emploigo.auth.application.registration;

import fr.emploigo.auth.application.quota.QuotaEnforcer;
import fr.emploigo.auth.application.quota.QuotaScope;
import fr.emploigo.config.ApiOnly;

import org.springframework.stereotype.Service;

import java.time.Duration;

/** Sends a new code for a pending registration, when the page of the request may ask for one. */
@Service
@ApiOnly
public class ResendRegistrationCodeUseCase {
    private final RegistrationTransactions transactions;
    private final QuotaEnforcer quota;
    private final RegistrationGatekeeper gatekeeper;
    private final RegistrationCodeMailer mailer;
    private final ConfirmationAttemptLimiter limiter;

    public ResendRegistrationCodeUseCase(
            RegistrationTransactions transactions,
            QuotaEnforcer quota,
            RegistrationGatekeeper gatekeeper,
            RegistrationCodeMailer mailer,
            ConfirmationAttemptLimiter limiter) {
        this.transactions = transactions;
        this.quota = quota;
        this.gatekeeper = gatekeeper;
        this.mailer = mailer;
        this.limiter = limiter;
    }

    /**
     * @param emailKey the email of the registration held by the session of the browser
     * @return the wait before the email may get another message
     */
    public Duration resend(String emailKey, String confirmationKey, String address) {
        gatekeeper.requireNoWait(emailKey, confirmationKey);
        gatekeeper.requireMail();
        quota.check(QuotaScope.REGISTRATION_MAIL, emailKey, address);
        var mail = transactions.resend(emailKey, confirmationKey);
        limiter.codeIssued(emailKey, confirmationKey);
        mailer.send(mail);
        return quota.sendDelay(QuotaScope.REGISTRATION_MAIL);
    }
}
