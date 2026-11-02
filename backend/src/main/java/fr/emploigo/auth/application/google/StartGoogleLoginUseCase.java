package fr.emploigo.auth.application.google;

import fr.emploigo.auth.application.quota.QuotaEnforcer;
import fr.emploigo.auth.application.quota.QuotaScope;
import fr.emploigo.auth.domain.AuthenticationSecrets;
import fr.emploigo.auth.domain.SessionIdentity;
import fr.emploigo.config.ApiOnly;

import org.springframework.stereotype.Service;

/** Starts a Google sign-in for a browser and keeps its flow: the redirection to Google follows. */
@Service
@ApiOnly
public class StartGoogleLoginUseCase {
    private final GoogleSignInClient client;
    private final GoogleTransactions transactions;
    private final QuotaEnforcer quota;
    private final GoogleSettings settings;

    public StartGoogleLoginUseCase(
            GoogleSignInClient client,
            GoogleTransactions transactions,
            QuotaEnforcer quota,
            GoogleSettings settings) {
        this.client = client;
        this.transactions = transactions;
        this.quota = quota;
        this.settings = settings;
    }

    /**
     * @param existingProof the proof this browser kept at an earlier start, or {@code null} for a
     *     browser without session: it gets a fresh proof, derived from a random value because no
     *     session identifier exists yet, and only the address limit stops it from starting again
     */
    public StartGoogleLoginResult start(
            String existingProof, SessionIdentity identity, String address) {
        String proof =
                existingProof != null
                        ? existingProof
                        : AuthenticationSecrets.browserProof(
                                AuthenticationSecrets.generate());
        // The quota comes first: a refused start must not write a session row.
        quota.check(QuotaScope.GOOGLE_START, "browser:" + proof, address);
        settings.requireEnabled();
        var request = client.authorization();
        transactions.start(request.getState(), proof, identity);
        return new StartGoogleLoginResult(request, proof);
    }
}
