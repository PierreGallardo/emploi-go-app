package fr.emploigo.auth.application.recovery;

import fr.emploigo.auth.application.mail.MailOperationChecker;
import fr.emploigo.auth.domain.MailFlow;
import fr.emploigo.config.ApiOnly;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Service
@ApiOnly
public class RecoveryMailOperationChecker implements MailOperationChecker {
    private final PasswordRecoveryRepository recoveries;

    public RecoveryMailOperationChecker(PasswordRecoveryRepository recoveries) {
        this.recoveries = recoveries;
    }

    @Override
    public MailFlow flow() {
        return MailFlow.RECOVERY;
    }

    @Override
    public String operationKind() {
        return "RESET";
    }

    @Override
    public boolean pending(UUID operation, Instant now) {
        var reset = operation == null ? null : recoveries.reset(operation);
        return reset != null && reset.status().equals("PENDING") && reset.expiresAt().isAfter(now);
    }
}
