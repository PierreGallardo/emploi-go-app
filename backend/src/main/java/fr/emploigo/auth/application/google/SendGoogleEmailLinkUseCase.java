package fr.emploigo.auth.application.google;

import fr.emploigo.auth.application.mail.AuthenticationMailDispatcher;
import fr.emploigo.auth.application.quota.QuotaEnforcer;
import fr.emploigo.auth.application.quota.QuotaScope;
import fr.emploigo.auth.domain.SessionIdentity;
import fr.emploigo.config.ApiOnly;

import org.springframework.stereotype.Service;

import java.util.UUID;

/** Sends the link that confirms an external Google address by email. */
@Service
@ApiOnly
public class SendGoogleEmailLinkUseCase {
    private final GoogleTransactions transactions;
    private final QuotaEnforcer quota;
    private final AuthenticationMailDispatcher dispatcher;
    private final GoogleSettings settings;

    public SendGoogleEmailLinkUseCase(
            GoogleTransactions transactions,
            QuotaEnforcer quota,
            AuthenticationMailDispatcher dispatcher,
            GoogleSettings settings) {
        this.transactions = transactions;
        this.quota = quota;
        this.dispatcher = dispatcher;
        this.settings = settings;
    }

    public void send(UUID id, String proof, SessionIdentity current, String address) {
        settings.requireEnabled();
        quota.check(QuotaScope.GOOGLE_MAIL, transactions.mailIdentity(id, proof, current), address);
        var mail = transactions.requestEmail(id, proof, current);
        if (!dispatcher.send(mail))
            throw new GoogleAuthenticationFailedException(
                    GoogleAuthenticationFailedException.Reason.UNAVAILABLE);
    }
}
