package fr.emploigo.auth.application.registration;

import fr.emploigo.auth.application.mail.MailOperationChecker;
import fr.emploigo.auth.domain.MailFlow;
import fr.emploigo.auth.domain.RegistrationCode;
import fr.emploigo.config.ApiOnly;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Service
@ApiOnly
public class RegistrationMailOperationChecker implements MailOperationChecker {
    private final RegistrationRepository registrations;

    public RegistrationMailOperationChecker(RegistrationRepository registrations) {
        this.registrations = registrations;
    }

    @Override
    public MailFlow flow() {
        return MailFlow.CONFIRMATION;
    }

    @Override
    public String operationKind() {
        return "CONFIRMATION";
    }

    @Override
    public boolean pending(UUID operation, Instant now) {
        var verification = operation == null ? null : registrations.verification(operation);
        return verification != null
                && verification.status().equals("PENDING")
                && verification.failedAttempts() < RegistrationCode.MAX_ATTEMPTS
                && verification.expiresAt().isAfter(now);
    }
}
