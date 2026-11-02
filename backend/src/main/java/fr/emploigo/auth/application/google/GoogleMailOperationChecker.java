package fr.emploigo.auth.application.google;

import fr.emploigo.auth.application.mail.MailOperationChecker;
import fr.emploigo.auth.domain.MailFlow;
import fr.emploigo.config.ApiOnly;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Service
@ApiOnly
public class GoogleMailOperationChecker implements MailOperationChecker {
    private final GoogleRepository google;

    public GoogleMailOperationChecker(GoogleRepository google) {
        this.google = google;
    }

    @Override
    public MailFlow flow() {
        return MailFlow.GOOGLE;
    }

    @Override
    public String operationKind() {
        return "VERIFY";
    }

    @Override
    public boolean pending(UUID operation, Instant now) {
        var verification = operation == null ? null : google.verification(operation);
        return verification != null
                && verification.status().equals("PENDING")
                && verification.expiresAt().isAfter(now);
    }
}
