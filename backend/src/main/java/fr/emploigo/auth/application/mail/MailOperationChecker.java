package fr.emploigo.auth.application.mail;

import fr.emploigo.auth.domain.MailFlow;

import java.time.Instant;
import java.util.UUID;

/**
 * Says whether the operation of a flow is still pending, so that its email is still sent: one
 * checker per flow, each in the subject that owns the operation.
 */
public interface MailOperationChecker {
    MailFlow flow();

    /** The kind of the email that this checker applies to: the other kinds are always sent. */
    String operationKind();

    boolean pending(UUID operation, Instant now);
}
