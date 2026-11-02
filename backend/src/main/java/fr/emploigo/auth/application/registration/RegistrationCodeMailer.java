package fr.emploigo.auth.application.registration;

import fr.emploigo.auth.application.mail.AuthenticationMail;
import fr.emploigo.auth.application.mail.AuthenticationMailDispatcher;
import fr.emploigo.config.ApiOnly;

import org.springframework.stereotype.Service;

/** Sends the code of a registration by email, and refuses when the email is not accepted. */
@Service
@ApiOnly
public class RegistrationCodeMailer {
    private final AuthenticationMailDispatcher dispatcher;

    public RegistrationCodeMailer(AuthenticationMailDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    public void send(AuthenticationMail mail) {
        if (!dispatcher.send(mail))
            throw new RegistrationFailedException(RegistrationFailedException.Reason.UNAVAILABLE);
    }
}
