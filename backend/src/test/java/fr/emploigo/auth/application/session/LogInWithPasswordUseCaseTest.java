package fr.emploigo.auth.application.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import fr.emploigo.auth.application.failure.AuthenticationFailedException;

import org.junit.jupiter.api.Test;

import java.util.UUID;

class LogInWithPasswordUseCaseTest {
    private static final String IDENTIFIER = "jeune@example.invalid";
    private static final String PASSWORD = "Une-Phrase-2026-fictive!";
    private static final String ADDRESS = "127.0.0.1";
    private static final String COOKIE = "cookie-fictif";
    private static final UUID FAMILY = UUID.randomUUID();

    private final PasswordAuthenticator passwords = mock(PasswordAuthenticator.class);
    private final AuthenticationSessions sessions = mock(AuthenticationSessions.class);
    private final LogInWithPasswordUseCase login =
            new LogInWithPasswordUseCase(passwords, sessions);

    @Test
    void checkedPasswordOpensTheSessionOfItsCredentials() {
        var credentials = new Credentials(7L, 0L);
        var grant =
                new AuthenticationGrant(
                        new AuthenticatedAccount(7L, null, "jeune", 0L, FAMILY), "secret");
        when(passwords.authenticate(IDENTIFIER, PASSWORD, ADDRESS)).thenReturn(credentials);
        when(sessions.create(credentials, SessionPersistence.REMEMBERED, COOKIE, FAMILY))
                .thenReturn(grant);

        var result =
                login.logIn(
                        IDENTIFIER, PASSWORD, ADDRESS, SessionPersistence.REMEMBERED, COOKIE, FAMILY);

        assertThat(result).as("grant").isSameAs(grant);
    }

    @Test
    void refusedPasswordOpensNoSession() {
        when(passwords.authenticate(any(), any(), any()))
                .thenThrow(AuthenticationFailedException.invalidCredentials());

        assertThatThrownBy(
                        () ->
                                login.logIn(
                                        IDENTIFIER,
                                        PASSWORD,
                                        ADDRESS,
                                        SessionPersistence.REMEMBERED,
                                        COOKIE,
                                        FAMILY))
                .as("login")
                .isInstanceOf(AuthenticationFailedException.class);

        verifyNoInteractions(sessions);
    }
}
