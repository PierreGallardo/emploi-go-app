package fr.emploigo.auth.application.registration;

import fr.emploigo.auth.application.failure.AuthenticationFailedException;
import fr.emploigo.auth.application.mail.AuthenticationMailSettings;
import fr.emploigo.config.ApiOnly;

import org.springframework.stereotype.Service;

/** Refuses a request for a registration before its work: no mail, or a page that waits. */
@Service
@ApiOnly
public class RegistrationGatekeeper {
    private final AuthenticationMailSettings mail;
    private final ConfirmationAttemptLimiter limiter;

    public RegistrationGatekeeper(
            AuthenticationMailSettings mail, ConfirmationAttemptLimiter limiter) {
        this.mail = mail;
        this.limiter = limiter;
    }

    public void requireMail() {
        if (!mail.enabled())
            throw new RegistrationFailedException(RegistrationFailedException.Reason.UNAVAILABLE);
    }

    /** A page whose attempts are blocked waits an hour for a new code, whatever its email. */
    public void requireNoWait(String emailKey, String confirmationKey) {
        limiter.waiting(emailKey, confirmationKey)
                .ifPresent(
                        wait -> {
                            throw AuthenticationFailedException.codesBlocked(wait);
                        });
    }
}
