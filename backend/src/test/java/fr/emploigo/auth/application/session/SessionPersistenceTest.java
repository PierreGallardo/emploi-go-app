package fr.emploigo.auth.application.session;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.UUID;

class SessionPersistenceTest {
    private static final UUID FAMILY = UUID.randomUUID();
    private static final String SECRET = "s".repeat(43);

    @Test
    void sessionIsRememberedWhenTheBrowserHoldsTheCookieOfItsFamily() {
        assertThat(SessionPersistence.keptBy(FAMILY + "." + SECRET, FAMILY))
                .isEqualTo(SessionPersistence.REMEMBERED);
    }

    @Test
    void cookieOfAnotherFamilyDoesNotRememberTheSession() {
        assertThat(SessionPersistence.keptBy(UUID.randomUUID() + "." + SECRET, FAMILY))
                .isEqualTo(SessionPersistence.UNTIL_BROWSER_CLOSES);
    }

    @ParameterizedTest(name = "cookie : {0}")
    @NullSource
    @ValueSource(strings = "malformed")
    void sessionWithoutAReadableCookieEndsWithTheBrowser(String cookie) {
        assertThat(SessionPersistence.keptBy(cookie, FAMILY))
                .isEqualTo(SessionPersistence.UNTIL_BROWSER_CLOSES);
    }
}
