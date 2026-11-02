package fr.emploigo.auth.application.session;

import java.time.Duration;
import java.util.UUID;

/** What a browser keeps of a login. */
public enum SessionPersistence {
    /** No reconnection cookie: the family lasts only as long as the session it proves. */
    UNTIL_BROWSER_CLOSES(AuthenticationSessions.SESSION_DURATION),
    /** The reconnection cookie, for {@code AuthenticationSessions.RECONNECTION_DURATION}. */
    REMEMBERED(AuthenticationSessions.RECONNECTION_DURATION);

    private final Duration familyDuration;

    SessionPersistence(Duration familyDuration) {
        this.familyDuration = familyDuration;
    }

    Duration familyDuration() {
        return familyDuration;
    }

    public static SessionPersistence chosen(boolean rememberMe) {
        return rememberMe ? REMEMBERED : UNTIL_BROWSER_CLOSES;
    }

    /**
     * What the browser keeps of the session of {@code family}: remembered when it holds the
     * reconnection cookie of that family. The secret is not checked: without the box, the family
     * id never leaves the server, so only a browser that was given the cookie can name it.
     */
    public static SessionPersistence keptBy(String cookie, UUID family) {
        return chosen(
                ReconnectionToken.parse(cookie)
                        .filter(token -> token.id().equals(family))
                        .isPresent());
    }
}
