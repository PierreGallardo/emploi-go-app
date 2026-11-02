package fr.emploigo.auth.application.registration;

import fr.emploigo.auth.application.account.AccountRepository;
import fr.emploigo.auth.application.session.AuthenticationGrant;
import fr.emploigo.auth.application.session.AuthenticationSessions;
import fr.emploigo.auth.application.session.Credentials;
import fr.emploigo.auth.application.session.SessionPersistence;
import fr.emploigo.auth.domain.AccountIdentifier;
import fr.emploigo.auth.domain.IdentifierTakenException;
import fr.emploigo.config.ApiOnly;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Gives its username to the account of a session that has none, the last step of a registration,
 * and opens the session again, kept by the browser as the session it replaces.
 */
@Service
@ApiOnly
public class ChooseUsernameUseCase {
    private final AccountRepository accounts;
    private final AuthenticationSessions sessions;

    public ChooseUsernameUseCase(AccountRepository accounts, AuthenticationSessions sessions) {
        this.accounts = accounts;
        this.sessions = sessions;
    }

    @Transactional
    public AuthenticationGrant choose(
            long accountId,
            long credentialVersion,
            UUID family,
            String username,
            String previousCookie) {
        String name;
        try {
            name = AccountIdentifier.username(username);
        } catch (IllegalArgumentException error) {
            throw new RegistrationFailedException(
                    RegistrationFailedException.Reason.INVALID_USERNAME);
        }
        sessions.verify(accountId, credentialVersion, family);
        boolean assigned;
        try {
            assigned = accounts.assignUsername(accountId, name);
        } catch (IdentifierTakenException error) {
            throw new RegistrationFailedException(
                    RegistrationFailedException.Reason.USERNAME_UNAVAILABLE);
        }
        if (!assigned)
            throw new RegistrationFailedException(
                    RegistrationFailedException.Reason.USERNAME_ALREADY_CHOSEN);
        // A password reset since the check of the session must not let it go on.
        return sessions.create(
                new Credentials(accountId, credentialVersion),
                SessionPersistence.keptBy(previousCookie, family),
                previousCookie,
                family);
    }
}
