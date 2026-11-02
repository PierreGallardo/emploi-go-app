package fr.emploigo.auth.domain;

import java.io.Serializable;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * The registration a browser asked for, kept in its server session: the email, the key whose hash
 * the registration keeps, the identifier the tab that asked for it keeps, and whether the session
 * its confirmation opens is remembered. The key never leaves the server, so a code only confirms
 * the registration in the browser that asked for it; the session is shared by the tabs of the
 * browser and by the next person on a shared computer, so a code only confirms it from the tab
 * that holds its identifier.
 */
public record RegistrationPage(
        String emailKey, String key, String id, boolean remembered, Instant lastCode)
        implements Serializable {
    public static final String ATTRIBUTE = "auth.registration";

    /** How long an unconfirmed registration keeps its email, from its last code. */
    public static final Duration LIFETIME = Duration.ofHours(24);

    /**
     * The page of a request for {@code email} under {@code key}, with a new identifier.
     *
     * @throws IllegalArgumentException if {@code email} is not an email
     */
    public static RegistrationPage issued(
            String email, String key, boolean remembered, Instant now) {
        return new RegistrationPage(
                AccountIdentifier.emailKey(email),
                key,
                UUID.randomUUID().toString(),
                remembered,
                now);
    }

    /** Whether the tab that sent {@code identifier} holds this page, while it lives. */
    public boolean heldBy(String identifier, Instant now) {
        return id.equals(identifier) && now.isBefore(lastCode.plus(LIFETIME));
    }

    public RegistrationPage renewed(Instant now) {
        return new RegistrationPage(emailKey, key, id, remembered, now);
    }

    @Override
    public String toString() {
        return "RegistrationPage[redacted]";
    }
}
