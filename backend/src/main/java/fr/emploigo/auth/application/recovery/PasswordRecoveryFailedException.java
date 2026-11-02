package fr.emploigo.auth.application.recovery;

public class PasswordRecoveryFailedException extends RuntimeException {
    public enum Reason {
        INVALID_INPUT,
        WEAK_PASSWORD,
        INVALID_LINK,
        EXPIRED_LINK,
        USED_LINK,
        REPLACED_LINK,
        UNAVAILABLE
    }

    private final Reason reason;

    public PasswordRecoveryFailedException(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
