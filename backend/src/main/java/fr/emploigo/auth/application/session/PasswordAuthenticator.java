package fr.emploigo.auth.application.session;

import fr.emploigo.auth.application.failure.AuthenticationFailedException;
import fr.emploigo.auth.application.quota.QuotaEnforcer;
import fr.emploigo.auth.domain.AccountIdentifier;
import fr.emploigo.auth.domain.AuthenticationSecrets;
import fr.emploigo.config.ApiOnly;

import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.Locale;

/**
 * The check of an identifier and a password, in a fixed order: the quota first (a delay in force
 * refuses before anything is read or computed), then the {@link PasswordChecker}. The quota counts
 * the identifier as typed, not the account, so the email and the username of one account have two
 * counters that an answer never links.
 */
@ApiOnly
@Service
public class PasswordAuthenticator {
    private static final String UNREADABLE = "invalid:";

    private final PasswordChecker passwords;
    private final QuotaEnforcer quota;

    public PasswordAuthenticator(PasswordChecker passwords, QuotaEnforcer quota) {
        this.passwords = passwords;
        this.quota = quota;
    }

    /**
     * The password of a registration that is not confirmed yet is refused like any wrong password:
     * only a new signup sends its code again.
     */
    public Credentials authenticate(String identifier, String password, String address) {
        var identity =
                AccountIdentifier.readableLoginKey(identifier)
                        .orElseGet(() -> unreadableIdentity(identifier));
        var attempt = quota.beginLogin(identity, address);
        var credentials =
                passwords
                        .check(identifier, password)
                        .orElseThrow(AuthenticationFailedException::invalidCredentials);
        attempt.succeed();
        return credentials;
    }

    /**
     * One counter per typed text, never one shared by all, which a client could exhaust for every
     * other text; the key has no {@code @}, so it is no email key.
     */
    private static String unreadableIdentity(String identifier) {
        return UNREADABLE
                + AuthenticationSecrets.hash(
                        Normalizer.normalize(identifier.strip(), Normalizer.Form.NFKC)
                                .toLowerCase(Locale.ROOT));
    }
}
