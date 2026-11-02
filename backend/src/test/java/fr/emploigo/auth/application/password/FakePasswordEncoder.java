package fr.emploigo.auth.application.password;

import org.springframework.security.crypto.password.PasswordEncoder;

/** The password encoder in memory: a fast, reversible stand-in that still tells passwords apart. */
public final class FakePasswordEncoder implements PasswordEncoder {
    @Override
    public String encode(CharSequence raw) {
        return "fake:" + raw;
    }

    @Override
    public boolean matches(CharSequence raw, String encoded) {
        return encode(raw).equals(encoded);
    }
}
