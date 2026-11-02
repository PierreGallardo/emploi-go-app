package fr.emploigo.auth.application.recovery;

import fr.emploigo.auth.application.mail.AuthenticationMailDispatcher;
import fr.emploigo.auth.application.password.PasswordHasher;
import fr.emploigo.auth.application.quota.QuotaEnforcer;
import fr.emploigo.auth.application.quota.QuotaScope;
import fr.emploigo.auth.domain.RegistrationPassword;
import fr.emploigo.config.ApiOnly;

import org.springframework.stereotype.Service;

/**
 * Changes the password from a reset link, in a fixed order: quota, mail, form of the password,
 * link, computation, write.
 */
@Service
@ApiOnly
public class ResetPasswordUseCase {
    private final RecoveryTransactions transactions;
    private final QuotaEnforcer quota;
    private final PasswordHasher hashing;
    private final RecoveryGatekeeper gatekeeper;
    private final AuthenticationMailDispatcher dispatcher;

    public ResetPasswordUseCase(
            RecoveryTransactions transactions,
            QuotaEnforcer quota,
            PasswordHasher hashing,
            RecoveryGatekeeper gatekeeper,
            AuthenticationMailDispatcher dispatcher) {
        this.transactions = transactions;
        this.quota = quota;
        this.hashing = hashing;
        this.gatekeeper = gatekeeper;
        this.dispatcher = dispatcher;
    }

    public void reset(String token, String password, String confirmation, String address) {
        quota.checkAddress(QuotaScope.RECOVERY_CONFIRM, address);
        gatekeeper.requireMail();
        if (!password.equals(confirmation))
            throw new PasswordRecoveryFailedException(
                    PasswordRecoveryFailedException.Reason.INVALID_INPUT);
        if (!RegistrationPassword.meetsRule(password))
            throw new PasswordRecoveryFailedException(
                    PasswordRecoveryFailedException.Reason.WEAK_PASSWORD);
        var link = gatekeeper.readLink(token);
        transactions.checkLink(link);
        var change = transactions.complete(link, hashing.encode(password));
        // No transaction is open: the login counters are reset here so the account can log in at
        // once.
        quota.resetLogin(change.emailKey(), change.username());
        // The notice is sent once; a failure is reported by the dispatcher, and never fails the
        // password change.
        dispatcher.send(change.mail());
    }
}
