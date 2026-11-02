package fr.emploigo.auth.application.mail;

import fr.emploigo.auth.application.confirmation.ConfirmationTokens;
import fr.emploigo.auth.domain.MailFlow;
import fr.emploigo.config.ApiOnly;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Sends an authentication email after the transaction that decided it committed. It holds no
 * transaction, lock or connection during the provider call, and it is the only path to the
 * provider. A link or a code that was replaced, used or expired before the send is not sent.
 */
@Service
@ApiOnly
public class AuthenticationMailDispatcher {
    private static final Logger LOG = LoggerFactory.getLogger(AuthenticationMailDispatcher.class);
    private final AuthenticationMailRenderer mails;
    private final AuthenticationMailSender transport;
    private final Map<MailFlow, MailOperationChecker> checkers;
    private final ConfirmationTokens tokens;
    private final Clock clock;

    public AuthenticationMailDispatcher(
            AuthenticationMailRenderer mails,
            AuthenticationMailSender transport,
            List<MailOperationChecker> checkers,
            ConfirmationTokens tokens,
            Clock clock) {
        this.mails = mails;
        this.transport = transport;
        this.checkers = checkersByFlow(checkers);
        this.tokens = tokens;
        this.clock = clock;
    }

    private static Map<MailFlow, MailOperationChecker> checkersByFlow(
            List<MailOperationChecker> checkers) {
        var byFlow = new EnumMap<MailFlow, MailOperationChecker>(MailFlow.class);
        for (var checker : checkers) {
            if (byFlow.put(checker.flow(), checker) != null)
                throw new IllegalStateException(
                        "Plusieurs vérificateurs pour le flux " + checker.flow());
        }
        for (var flow : MailFlow.values()) {
            if (!byFlow.containsKey(flow))
                throw new IllegalStateException("Aucun vérificateur pour le flux " + flow);
        }
        return byFlow;
    }

    /**
     * @return whether the provider accepted the email
     */
    public boolean send(AuthenticationMail mail) {
        try {
            if (!mail.keyId().equals(tokens.keyId()) || !operationPending(mail, clock.instant()))
                return false;
        } catch (DataAccessException error) {
            LOG.warn(
                    "Vérification d’envoi indisponible ({}, {})",
                    mail.flow().label(),
                    error.getClass().getSimpleName());
            return false;
        }
        var message = mails.render(mail);
        var result =
                transport.send(
                        message.idempotencyKey(),
                        mail.recipient(),
                        mail.sender(),
                        message.subject(),
                        message.text());
        if (result != AuthenticationMailSender.Result.SENT)
            LOG.warn("Envoi d’email non accepté ({}, {})", mail.flow().label(), result);
        return result == AuthenticationMailSender.Result.SENT;
    }

    private boolean operationPending(AuthenticationMail mail, Instant now) {
        var checker = checkers.get(mail.flow());
        return !mail.kind().equals(checker.operationKind())
                || checker.pending(mail.operationId(), now);
    }
}
