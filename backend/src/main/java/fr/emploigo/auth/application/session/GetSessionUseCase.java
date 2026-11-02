package fr.emploigo.auth.application.session;

import fr.emploigo.config.ApiOnly;

import org.springframework.stereotype.Service;

import java.util.UUID;

/** Checks that the session of a request still holds, and reads the account it belongs to. */
@ApiOnly
@Service
public class GetSessionUseCase {
    private final AuthenticationSessions sessions;

    public GetSessionUseCase(AuthenticationSessions sessions) {
        this.sessions = sessions;
    }

    public AuthenticatedAccount get(long accountId, long credentialVersion, UUID familyId) {
        return sessions.verify(accountId, credentialVersion, familyId);
    }
}
