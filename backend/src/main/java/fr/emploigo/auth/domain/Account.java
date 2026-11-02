package fr.emploigo.auth.domain;

import java.util.UUID;

public record Account(
        Long id,
        UUID publicId,
        String username,
        String emailKey,
        String passwordHash,
        boolean emailVerified,
        boolean enabled,
        String role,
        long credentialVersion) {
    private static final String YOUNG_PERSON = "JEUNE";

    public Account(
            String username,
            String email,
            String passwordHash,
            boolean emailVerified,
            boolean enabled) {
        this(
                null,
                null,
                AccountIdentifier.username(username),
                AccountIdentifier.emailKey(email),
                passwordHash,
                emailVerified,
                enabled,
                YOUNG_PERSON,
                0);
    }

    /** An account whose email is verified and which is enabled, from keys already normalized. */
    public static Account confirmed(String username, String emailKey, String passwordHash) {
        return new Account(
                null,
                null,
                username,
                emailKey,
                passwordHash,
                true,
                true,
                YOUNG_PERSON,
                0);
    }

    /** A confirmed account that chooses its username after its creation. */
    public static Account withoutUsername(String emailKey, String passwordHash) {
        return confirmed(null, emailKey, passwordHash);
    }

    public boolean hasUsername() {
        return username != null;
    }

    /** The only rule that lets an account authenticate, whatever the way in. */
    public boolean mayAuthenticate() {
        return enabled && emailVerified && YOUNG_PERSON.equals(role);
    }

    public boolean local() {
        return passwordHash != null;
    }

    public boolean mayLogInWithPassword() {
        return mayAuthenticate() && local();
    }

    @Override
    public String toString() {
        return "Account[redacted]";
    }
}
