package fr.emploigo.auth.application.google;

import fr.emploigo.auth.application.google.GoogleAuthenticationFailedException.Reason;
import fr.emploigo.auth.application.mail.AuthenticationMail;
import fr.emploigo.auth.application.mail.AuthenticationMailDispatcher;
import fr.emploigo.auth.application.quota.QuotaEnforcer;
import fr.emploigo.auth.application.quota.QuotaScope;
import fr.emploigo.auth.domain.IdentifierTakenException;
import fr.emploigo.auth.domain.SessionIdentity;
import fr.emploigo.config.ApiOnly;

import org.springframework.stereotype.Service;

import java.util.UUID;

/** Links a Google identity to the verified local account, then tells its owner by email. */
@Service
@ApiOnly
public class LinkGoogleAccountUseCase {
    private final GoogleTransactions transactions;
    private final QuotaEnforcer quota;
    private final AuthenticationMailDispatcher dispatcher;
    private final GoogleSettings settings;

    public LinkGoogleAccountUseCase(
            GoogleTransactions transactions,
            QuotaEnforcer quota,
            AuthenticationMailDispatcher dispatcher,
            GoogleSettings settings) {
        this.transactions = transactions;
        this.quota = quota;
        this.dispatcher = dispatcher;
        this.settings = settings;
    }

    public void link(UUID id, String proof, SessionIdentity current, String address) {
        settings.requireEnabled();
        quota.check(QuotaScope.GOOGLE_LINK, "pending:" + id, address);
        AuthenticationMail mail;
        try {
            mail = transactions.link(id, proof, current);
        } catch (IdentifierTakenException error) {
            throw new GoogleAuthenticationFailedException(linkRefusal(error));
        }
        // The notice is sent once, after the link is committed; a failure is reported by the
        // dispatcher and never fails the link.
        dispatcher.send(mail);
    }

    /** Only a Google identity can be taken by a link: the account of the link is already known. */
    private static Reason linkRefusal(IdentifierTakenException error) {
        return switch (error.identifier()) {
            case GOOGLE_SUBJECT, GOOGLE_ACCOUNT -> Reason.ALREADY_LINKED;
            case USERNAME, EMAIL -> throw error;
        };
    }
}
