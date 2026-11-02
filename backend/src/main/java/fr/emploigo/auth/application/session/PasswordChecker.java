package fr.emploigo.auth.application.session;

import java.util.Optional;

/**
 * The check of a password against the account that an identifier names, by email or by username.
 * An unknown identifier and an account that may not log in are empty like a wrong password, after
 * a computation of the same cost.
 */
public interface PasswordChecker {
    /**
     * @param identifier the identifier as typed
     * @return the account proven by the password, or empty whatever the reason of the refusal
     * @throws fr.emploigo.auth.application.password.PasswordHashingBusyException when the limit
     *     of simultaneous computations is reached
     */
    Optional<Credentials> check(String identifier, String password);
}
