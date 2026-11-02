package fr.emploigo.auth.application;

import fr.emploigo.auth.application.password.HashingGate;
import fr.emploigo.auth.application.password.PasswordHasher;
import fr.emploigo.auth.application.quota.AuthenticationRateLimiter;
import fr.emploigo.auth.application.quota.LoginAttemptLimiter;
import fr.emploigo.support.IntegrationTest;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Base of the integration tests of {@code auth}. Its beans are the observed ones declared by
 * {@link AuthenticationTestDoubles}, so it adds no configuration of its own.
 */
public abstract class AuthenticationIntegrationTest extends IntegrationTest {
    /**
     * The real encoder, observed: a test checks that a refusal computed nothing, or holds a
     * computation ({@link #hashingGate()}).
     */
    @Autowired protected PasswordEncoder passwords;

    @Autowired protected PasswordHasher hashing;

    /**
     * The real counters, observed: a test checks when they are called (outside any transaction, for
     * example).
     */
    @Autowired protected AuthenticationRateLimiter limiter;

    /**
     * The real counters of the login, observed: a test makes one call fail, as Redis would between
     * two calls.
     */
    @Autowired protected LoginAttemptLimiter loginLimiter;

    /**
     * A gate that holds password verifications inside the encoder (see {@link HashingGate}). Open
     * it in a {@code try} block: closing it releases the held computations, whatever the test did.
     */
    protected HashingGate hashingGate() {
        return new HashingGate(hashing, passwords);
    }

    /**
     * Simulates the cooldown of the email flow {@code scope} (for example {@code
     * registration-mail}) expiring for {@code identity}, without sleeping or weakening application
     * configuration.
     */
    protected void permitNextMail(String scope, String identity) {
        AuthenticationTestSupport.permitNextMail(redis, scope, identity);
    }
}
