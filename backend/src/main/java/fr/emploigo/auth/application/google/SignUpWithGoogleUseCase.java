package fr.emploigo.auth.application.google;

import fr.emploigo.auth.application.google.GoogleAuthenticationFailedException.Reason;
import fr.emploigo.auth.application.quota.QuotaEnforcer;
import fr.emploigo.auth.application.quota.QuotaScope;
import fr.emploigo.auth.application.session.AuthenticationGrant;
import fr.emploigo.auth.domain.IdentifierTakenException;
import fr.emploigo.auth.domain.IdentifierTakenException.Identifier;
import fr.emploigo.auth.domain.SessionIdentity;
import fr.emploigo.config.ApiOnly;

import org.springframework.stereotype.Service;

import java.util.UUID;

/** Creates the account of a pending Google flow, with a username chosen by the person. */
@Service
@ApiOnly
public class SignUpWithGoogleUseCase {
    private final GoogleTransactions transactions;
    private final QuotaEnforcer quota;
    private final GoogleSettings settings;

    public SignUpWithGoogleUseCase(
            GoogleTransactions transactions, QuotaEnforcer quota, GoogleSettings settings) {
        this.transactions = transactions;
        this.quota = quota;
        this.settings = settings;
    }

    public AuthenticationGrant signUp(
            UUID id,
            String proof,
            SessionIdentity current,
            String username,
            String cookie,
            UUID family,
            String address) {
        settings.requireEnabled();
        quota.check(QuotaScope.GOOGLE_COMPLETE, "pending:" + id, address);
        try {
            return transactions.signup(id, proof, current, username, cookie, family);
        } catch (IdentifierTakenException error) {
            throw new GoogleAuthenticationFailedException(refusalOf(error.identifier()));
        }
    }

    private static Reason refusalOf(Identifier identifier) {
        return switch (identifier) {
            case USERNAME -> Reason.USERNAME_UNAVAILABLE;
            case EMAIL -> Reason.ACCOUNT_ACCESS_REQUIRED;
            case GOOGLE_SUBJECT, GOOGLE_ACCOUNT -> Reason.ALREADY_LINKED;
        };
    }
}
