package fr.emploigo.auth.application.google;

import fr.emploigo.auth.application.quota.QuotaEnforcer;
import fr.emploigo.auth.application.quota.QuotaScope;
import fr.emploigo.auth.application.session.AuthenticationToken;
import fr.emploigo.config.ApiOnly;

import org.springframework.stereotype.Service;

/** Confirms an external Google address from its link, without creating an account or a session. */
@Service
@ApiOnly
public class ConfirmGoogleEmailUseCase {
    private final GoogleTransactions transactions;
    private final QuotaEnforcer quota;
    private final GoogleSettings settings;

    public ConfirmGoogleEmailUseCase(
            GoogleTransactions transactions, QuotaEnforcer quota, GoogleSettings settings) {
        this.transactions = transactions;
        this.quota = quota;
        this.settings = settings;
    }

    public void confirm(String token, String address) {
        settings.requireEnabled();
        quota.check(
                QuotaScope.GOOGLE_EMAIL_CONFIRM,
                token == null ? "invalid" : token.substring(0, Math.min(36, token.length())),
                address);
        // Before the transaction: a text that is not a link is refused without taking a connection
        // of the pool.
        transactions.confirmEmail(
                AuthenticationToken.parse(token)
                        .orElseThrow(
                                () ->
                                        new GoogleAuthenticationFailedException(
                                                GoogleAuthenticationFailedException.Reason
                                                        .INVALID_LINK)));
    }
}
