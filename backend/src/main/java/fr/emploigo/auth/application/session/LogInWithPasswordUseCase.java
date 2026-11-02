package fr.emploigo.auth.application.session;

import fr.emploigo.config.ApiOnly;

import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * The login of a person with an identifier and a password: the check is {@link
 * PasswordAuthenticator}'s, and the session that follows is {@link AuthenticationSessions}'s.
 */
@ApiOnly
@Service
public class LogInWithPasswordUseCase {
    private final PasswordAuthenticator passwords;
    private final AuthenticationSessions sessions;

    public LogInWithPasswordUseCase(
            PasswordAuthenticator passwords, AuthenticationSessions sessions) {
        this.passwords = passwords;
        this.sessions = sessions;
    }

    /**
     * @param previousCookie the reconnection cookie of this browser, revoked with its family
     */
    public AuthenticationGrant logIn(
            String identifier,
            String password,
            String address,
            SessionPersistence persistence,
            String previousCookie,
            UUID previousFamily) {
        var credentials = passwords.authenticate(identifier, password, address);
        return sessions.create(credentials, persistence, previousCookie, previousFamily);
    }
}
