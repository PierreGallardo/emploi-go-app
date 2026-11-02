package fr.emploigo.auth.application.google;

public class GoogleAuthenticationFailedException extends RuntimeException {
    public enum Reason {
        INVALID_INPUT,
        INVALID_FLOW,
        EXPIRED_FLOW,
        CANCELLED,
        PROVIDER_REJECTED,
        ACCOUNT_ACCESS_REQUIRED,
        EMAIL_CONFIRMATION_REQUIRED,
        ALREADY_LINKED,
        INVALID_USERNAME,
        USERNAME_UNAVAILABLE,
        INVALID_LINK,
        EXPIRED_LINK,
        USED_LINK,
        REPLACED_LINK,
        UNAVAILABLE
    }

    private final Reason reason;

    public GoogleAuthenticationFailedException(Reason reason) {
        super("Parcours Google refusé : " + reason.name());
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
