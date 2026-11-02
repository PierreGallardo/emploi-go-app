package fr.emploigo.auth.application.registration;

import fr.emploigo.auth.application.session.AuthenticationGrant;

import java.time.Duration;

/**
 * The outcome of a code. A refusal is returned, not thrown, because the failed attempt it counts
 * must be committed with the transaction. Exactly one of the grant, the refusal, the wait and the
 * foreign page is set. A foreign page is one no registration belongs to: its attempts are counted
 * apart, so that its answers do not differ from those of the page of a registration.
 */
public record ConfirmRegistrationResult(
        AuthenticationGrant grant,
        RegistrationFailedException.Reason refusal,
        Duration retryAfter,
        boolean foreignPage) {
    static ConfirmRegistrationResult granted(AuthenticationGrant grant) {
        return new ConfirmRegistrationResult(grant, null, null, false);
    }

    static ConfirmRegistrationResult refused(RegistrationFailedException.Reason reason) {
        return new ConfirmRegistrationResult(null, reason, null, false);
    }

    static ConfirmRegistrationResult blocked(Duration retryAfter) {
        return new ConfirmRegistrationResult(null, null, retryAfter, false);
    }

    static ConfirmRegistrationResult ofForeignPage() {
        return new ConfirmRegistrationResult(null, null, null, true);
    }

    @Override
    public String toString() {
        return "ConfirmRegistrationResult[redacted]";
    }
}
