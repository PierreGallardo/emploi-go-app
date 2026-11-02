package fr.emploigo.auth.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

/**
 * Passwords for the tests that go through a registration or a reset, where the password rule
 * applies.
 */
public final class TestPasswords {
    private TestPasswords() {}

    /** A new random password that meets the registration rule. */
    public static String random() {
        String password = UUID.randomUUID() + " Une phrase 🔑 ";
        assertThat(RegistrationPassword.meetsRule(password))
                .as("given: random password meets the rule")
                .isTrue();
        return password;
    }
}
