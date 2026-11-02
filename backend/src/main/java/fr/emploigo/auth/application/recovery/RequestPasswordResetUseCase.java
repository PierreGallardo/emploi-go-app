package fr.emploigo.auth.application.recovery;

import fr.emploigo.auth.application.mail.AuthenticationMailDispatcher;
import fr.emploigo.auth.application.quota.QuotaEnforcer;
import fr.emploigo.auth.application.quota.QuotaScope;
import fr.emploigo.auth.domain.AccountIdentifier;
import fr.emploigo.config.ApiOnly;

import org.springframework.stereotype.Service;

import java.time.Duration;

/** Sends a reset link to an address; the answer is the same whether or not an account has it. */
@Service
@ApiOnly
public class RequestPasswordResetUseCase {
    private final RecoveryTransactions transactions;
    private final QuotaEnforcer quota;
    private final RecoveryGatekeeper gatekeeper;
    private final AuthenticationMailDispatcher dispatcher;

    public RequestPasswordResetUseCase(
            RecoveryTransactions transactions,
            QuotaEnforcer quota,
            RecoveryGatekeeper gatekeeper,
            AuthenticationMailDispatcher dispatcher) {
        this.transactions = transactions;
        this.quota = quota;
        this.gatekeeper = gatekeeper;
        this.dispatcher = dispatcher;
    }

    /**
     * @return the wait before the address may get another message, the same with or without an
     *     account
     */
    public Duration request(String email, String address) {
        String key;
        try {
            key = AccountIdentifier.emailKey(email);
        } catch (IllegalArgumentException error) {
            throw new PasswordRecoveryFailedException(
                    PasswordRecoveryFailedException.Reason.INVALID_INPUT);
        }
        gatekeeper.requireMail();
        quota.check(QuotaScope.RECOVERY_MAIL, key, address);
        var mail = transactions.request(key);
        if (!dispatcher.send(mail))
            throw new PasswordRecoveryFailedException(
                    PasswordRecoveryFailedException.Reason.UNAVAILABLE);
        return quota.sendDelay(QuotaScope.RECOVERY_MAIL);
    }
}
