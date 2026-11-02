package fr.emploigo.auth.application.registration;

import fr.emploigo.auth.application.failure.AuthenticationFailedException;
import fr.emploigo.auth.application.quota.QuotaEnforcer;
import fr.emploigo.auth.application.quota.QuotaScope;
import fr.emploigo.auth.application.session.AuthenticationGrant;
import fr.emploigo.auth.application.session.SessionPersistence;
import fr.emploigo.auth.domain.RegistrationCode;
import fr.emploigo.config.ApiOnly;

import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Confirms a registration with the code sent by email, and opens a session for the new account,
 * which chooses its username next.
 */
@Service
@ApiOnly
public class ConfirmRegistrationUseCase {
    private final RegistrationTransactions transactions;
    private final QuotaEnforcer quota;
    private final ConfirmationAttemptLimiter limiter;

    public ConfirmRegistrationUseCase(
            RegistrationTransactions transactions,
            QuotaEnforcer quota,
            ConfirmationAttemptLimiter limiter) {
        this.transactions = transactions;
        this.quota = quota;
        this.limiter = limiter;
    }

    public AuthenticationGrant confirm(
            String emailKey,
            String typedCode,
            String confirmationKey,
            SessionPersistence persistence,
            String previousCookie,
            UUID previousFamily,
            String address) {
        // The identity is the page: a third party cannot use up the attempts of the holder's page.
        quota.check(QuotaScope.CONFIRMATION, emailKey + "\0" + confirmationKey, address);
        // Before the transaction: a text that is not a code is refused without taking a connection
        // of the pool, and without counting an attempt.
        String code =
                RegistrationCode.normalize(typedCode)
                        .orElseThrow(
                                () ->
                                        new RegistrationFailedException(
                                                RegistrationFailedException.Reason.INVALID_CODE));
        var result =
                transactions.confirm(
                        emailKey,
                        code,
                        confirmationKey,
                        persistence,
                        previousCookie,
                        previousFamily);
        if (result.foreignPage()) throw foreignPage(emailKey, confirmationKey);
        if (result.retryAfter() != null)
            throw AuthenticationFailedException.codesBlocked(result.retryAfter());
        if (result.refusal() != null) throw new RegistrationFailedException(result.refusal());
        return result.grant();
    }

    /**
     * A page no registration belongs to has its attempts counted apart and answered like those of a
     * registration. The counters being out of reach changes nothing for it: it cannot confirm.
     */
    private RuntimeException foreignPage(String emailKey, String confirmationKey) {
        var answer = limiter.fail(emailKey, confirmationKey);
        return switch (answer.state()) {
            case SPENT ->
                    new RegistrationFailedException(
                            RegistrationFailedException.Reason.CODE_ATTEMPTS_EXCEEDED);
            case WAITING -> AuthenticationFailedException.codesBlocked(answer.delay());
            case EXPIRED ->
                    new RegistrationFailedException(
                            RegistrationFailedException.Reason.CODE_EXPIRED);
            case WRONG, UNAVAILABLE ->
                    new RegistrationFailedException(
                            RegistrationFailedException.Reason.INVALID_CODE);
        };
    }
}
