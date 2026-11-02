package fr.emploigo.auth.infrastructure.session;

import fr.emploigo.auth.application.password.PasswordHasher;

import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * The encoder that Spring Security's login computes with: every computation, its dummy hash
 * included, goes through {@link PasswordHasher} and its limit.
 */
final class CappedPasswordEncoder implements PasswordEncoder {
    private final PasswordHasher hashing;

    CappedPasswordEncoder(PasswordHasher hashing) {
        this.hashing = hashing;
    }

    @Override
    public String encode(CharSequence raw) {
        return hashing.encode(raw.toString());
    }

    @Override
    public boolean matches(CharSequence raw, String encoded) {
        return hashing.matches(raw.toString(), encoded);
    }
}
