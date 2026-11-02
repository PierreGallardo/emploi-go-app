package fr.emploigo.auth.application.google;

import fr.emploigo.auth.application.quota.QuotaEnforcer;
import fr.emploigo.auth.application.quota.QuotaScope;
import fr.emploigo.auth.domain.SessionIdentity;
import fr.emploigo.config.ApiOnly;

import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Completes the Google sign-in when Google redirects back: the identity opens a session, or a
 * pending link.
 */
@Service
@ApiOnly
public class HandleGoogleCallbackUseCase {
    private final GoogleSignInClient client;
    private final GoogleTransactions transactions;
    private final QuotaEnforcer quota;
    private final GoogleSettings settings;

    public HandleGoogleCallbackUseCase(
            GoogleSignInClient client,
            GoogleTransactions transactions,
            QuotaEnforcer quota,
            GoogleSettings settings) {
        this.client = client;
        this.transactions = transactions;
        this.quota = quota;
        this.settings = settings;
    }

    public GoogleTransactions.Callback handle(
            OAuth2AuthorizationRequest original,
            String proof,
            SessionIdentity current,
            String code,
            String state,
            String error,
            String previousCookie,
            UUID previousFamily,
            String address) {
        // The only application check of the state: it must precede the consumption of the flow.
        if (original == null
                || proof == null
                || state == null
                || !state.equals(original.getState()))
            throw new GoogleAuthenticationFailedException(
                    GoogleAuthenticationFailedException.Reason.INVALID_FLOW);
        quota.check(QuotaScope.GOOGLE_CALLBACK, "browser:" + proof, address);
        var scope = transactions.consume(state, proof, current);
        settings.requireEnabled();
        var identity =
                switch (client.verify(original, code, state, error)) {
                    case GoogleSignInClient.Verification.Verified verified -> verified.identity();
                    case GoogleSignInClient.Verification.Refused refused ->
                            throw new GoogleAuthenticationFailedException(
                                    failureReason(refused.reason()));
                };
        return transactions.accept(
                identity, scope, current, state, previousCookie, previousFamily);
    }

    private static GoogleAuthenticationFailedException.Reason failureReason(
            GoogleSignInClient.Refusal refusal) {
        return switch (refusal) {
            case INVALID_FLOW -> GoogleAuthenticationFailedException.Reason.INVALID_FLOW;
            case CANCELLED -> GoogleAuthenticationFailedException.Reason.CANCELLED;
            case PROVIDER_REJECTED -> GoogleAuthenticationFailedException.Reason.PROVIDER_REJECTED;
            case UNAVAILABLE -> GoogleAuthenticationFailedException.Reason.UNAVAILABLE;
        };
    }
}
