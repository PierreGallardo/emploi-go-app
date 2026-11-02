package fr.emploigo.auth.application.recovery;

import fr.emploigo.auth.application.mail.AuthenticationMailSettings;
import fr.emploigo.auth.application.session.AuthenticationToken;
import fr.emploigo.config.ApiOnly;

import org.springframework.stereotype.Service;

/** Refuses a recovery request before its work: no mail, or a text that is not a link. */
@Service
@ApiOnly
class RecoveryGatekeeper {
    private final AuthenticationMailSettings mail;

    RecoveryGatekeeper(AuthenticationMailSettings mail) {
        this.mail = mail;
    }

    void requireMail() {
        if (!mail.enabled())
            throw new PasswordRecoveryFailedException(
                    PasswordRecoveryFailedException.Reason.UNAVAILABLE);
    }

    /**
     * Read before any transaction: a text that is not a link is refused without taking a connection
     * of the pool.
     */
    AuthenticationToken readLink(String token) {
        return AuthenticationToken.parse(token)
                .orElseThrow(
                        () ->
                                new PasswordRecoveryFailedException(
                                        PasswordRecoveryFailedException.Reason.INVALID_LINK));
    }
}
