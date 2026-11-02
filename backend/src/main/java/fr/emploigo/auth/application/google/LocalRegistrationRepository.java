package fr.emploigo.auth.application.google;

/**
 * The registrations with a password, which an account that Google creates for their email leaves
 * unconfirmable. The write runs in the transaction of the use case.
 */
public interface LocalRegistrationRepository {
    /**
     * Expires the pending registration of this email whatever its life, and forgets its
     * credentials. It takes the row lock, so it waits for a confirmation of that registration.
     */
    void expirePending(String email);
}
