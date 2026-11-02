package fr.emploigo.auth.application.google;

import fr.emploigo.auth.application.session.AuthenticationGrant;
import fr.emploigo.auth.application.session.PasswordAuthenticator;
import fr.emploigo.auth.domain.SessionIdentity;
import fr.emploigo.config.ApiOnly;

import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Checks the local account that the person names for a pending Google flow, and opens a session
 * for it: the link is confirmed later, by {@link LinkGoogleAccountUseCase}.
 */
@Service
@ApiOnly
public class VerifyLocalAccountForGoogleUseCase {
    private final GoogleTransactions transactions;
    private final PasswordAuthenticator passwords;
    private final GoogleSettings settings;

    public VerifyLocalAccountForGoogleUseCase(
            GoogleTransactions transactions,
            PasswordAuthenticator passwords,
            GoogleSettings settings) {
        this.transactions = transactions;
        this.passwords = passwords;
        this.settings = settings;
    }

    public AuthenticationGrant verify(
            UUID id,
            String proof,
            SessionIdentity current,
            String identifier,
            String password,
            String cookie,
            UUID family,
            String address) {
        settings.requireEnabled();
        var credentials = passwords.authenticate(identifier, password, address);
        return transactions.access(id, proof, current, credentials, cookie, family);
    }
}
