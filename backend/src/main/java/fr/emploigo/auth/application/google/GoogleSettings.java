package fr.emploigo.auth.application.google;

/** The settings of the Google flows that the use cases read: whether they are on, and the origin. */
public record GoogleSettings(boolean enabled, String origin) {
    /** Refuses a Google flow while Google is disabled, before any other check. */
    public void requireEnabled() {
        if (!enabled)
            throw new GoogleAuthenticationFailedException(
                    GoogleAuthenticationFailedException.Reason.UNAVAILABLE);
    }
}
