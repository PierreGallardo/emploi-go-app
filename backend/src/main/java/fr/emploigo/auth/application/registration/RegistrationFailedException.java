package fr.emploigo.auth.application.registration;

public class RegistrationFailedException extends RuntimeException {
    public enum Reason {
        INVALID_INPUT,
        WEAK_PASSWORD,
        INVALID_USERNAME,
        USERNAME_UNAVAILABLE,
        USERNAME_ALREADY_CHOSEN,
        ACCOUNT_ALREADY_EXISTS,
        INVALID_CODE,
        CODE_EXPIRED,
        CODE_REPLACED,
        CODE_USED,
        CODE_ATTEMPTS_EXCEEDED,
        NO_PENDING_REGISTRATION,
        UNAVAILABLE
    }

    private final Reason reason;

    public RegistrationFailedException(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
