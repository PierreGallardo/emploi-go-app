package fr.emploigo.auth.application.mail;

import fr.emploigo.auth.domain.MailFlow;

import java.util.UUID;

/**
 * An authentication email decided by a committed transaction and sent right after it. It holds what
 * the message is rendered from. {@code id} names the message for the provider's idempotency key,
 * and {@code operationId} is the operation that the link or the code opens, when there is one.
 */
public record AuthenticationMail(
        MailFlow flow,
        String kind,
        UUID operationId,
        String recipient,
        String sender,
        String origin,
        String keyId,
        UUID id) {
    public static AuthenticationMail of(
            MailFlow flow,
            String kind,
            UUID operationId,
            String recipient,
            String sender,
            String origin,
            String keyId) {
        return new AuthenticationMail(
                flow, kind, operationId, recipient, sender, origin, keyId, UUID.randomUUID());
    }

    @Override
    public String toString() {
        return "AuthenticationMail[redacted]";
    }
}
