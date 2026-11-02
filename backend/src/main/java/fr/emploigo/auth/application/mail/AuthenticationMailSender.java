package fr.emploigo.auth.application.mail;

/**
 * The provider that delivers the authentication emails. A repeat of a send with the same
 * idempotency key and body is answered as the same message.
 */
public interface AuthenticationMailSender {
    /** What the provider answered for a send. */
    enum Result {
        /** Accepted: the message is sent. */
        SENT,
        /** Unknown whether the provider accepted it: the message may have been sent. */
        UNCERTAIN,
        /** Refused: the message is not sent. */
        FAILED
    }

    Result send(
            String idempotencyKey, String recipient, String sender, String subject, String text);
}
