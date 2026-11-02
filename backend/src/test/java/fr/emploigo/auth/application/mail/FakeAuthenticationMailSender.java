package fr.emploigo.auth.application.mail;

import java.util.ArrayList;
import java.util.List;

/**
 * The provider in memory: it records each message it is asked to send, and answers the result that
 * the test chose, {@link Result#SENT} by default.
 */
public final class FakeAuthenticationMailSender implements AuthenticationMailSender {
    /** A message as the provider receives it. */
    public record Sent(
            String idempotencyKey, String recipient, String sender, String subject, String text) {}

    private final List<Sent> sent = new ArrayList<>();
    private Result answer = Result.SENT;

    public void answer(Result result) {
        answer = result;
    }

    public List<Sent> sent() {
        return List.copyOf(sent);
    }

    @Override
    public Result send(
            String idempotencyKey, String recipient, String sender, String subject, String text) {
        sent.add(new Sent(idempotencyKey, recipient, sender, subject, text));
        return answer;
    }
}
