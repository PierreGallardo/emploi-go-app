package fr.emploigo.auth.api.session;

import static fr.emploigo.support.HostCookies.RECONNECTABLE_COOKIE;
import static fr.emploigo.support.HostCookies.RECONNECTION_COOKIE;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import fr.emploigo.auth.application.AuthenticationIntegrationTest;
import fr.emploigo.auth.application.failure.AuthenticationFailedException;
import fr.emploigo.auth.application.session.AuthenticationSessions;
import fr.emploigo.auth.application.session.ResumeSessionUseCase;
import fr.emploigo.auth.domain.AuthenticationSecrets;
import fr.emploigo.support.PoolHold;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.net.http.HttpResponse;
import java.util.UUID;

import javax.sql.DataSource;

/**
 * A malformed reconnection cookie is refused before the database, even with the pool held: anyone
 * can send one. The formats are unit-tested in {@code AuthenticationTokenTest}.
 */
class ReconnectionPoolIT extends AuthenticationIntegrationTest {
    private static final String MALFORMED = "a".repeat(80);

    @Autowired ResumeSessionUseCase resumeSession;
    @Autowired AuthenticationSessions sessions;
    @Autowired DataSource dataSource;

    @Test
    void resumeRefusesAMalformedCookieWithoutTakingAConnection() throws Exception {
        try (var _ = PoolHold.ofAll(dataSource)) {
            assertThatThrownBy(() -> resumeSession.resume(MALFORMED))
                    .as("refusal")
                    .isInstanceOfSatisfying(
                            AuthenticationFailedException.class,
                            refusal ->
                                    assertThat(refusal.reason())
                                            .as("reason")
                                            .isEqualTo(
                                                    AuthenticationFailedException.Reason
                                                            .RECONNECTION_REFUSED));
        }
    }

    @Test
    void revokeIgnoresAMalformedCookieWithoutTakingAConnection() throws Exception {
        try (var _ = PoolHold.ofAll(dataSource)) {
            assertThatCode(() -> sessions.revoke(MALFORMED, null))
                    .as("revocation")
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void theRoutesAnswerAMalformedCookieWhileThePoolIsHeld() throws Exception {
        var browser = newBrowser();
        String csrf = browser.csrf();
        browser.cookies.put(RECONNECTION_COOKIE, MALFORMED);
        int resumed;
        int loggedOut;

        try (var _ = PoolHold.ofAll(dataSource)) {
            resumed = browser.post("resume", "", csrf).statusCode();
            loggedOut = browser.post("logout", "", csrf).statusCode();
        }

        assertThat(resumed).as("status of resume").isEqualTo(401);
        assertThat(loggedOut).as("status of logout").isEqualTo(204);
    }

    @Test
    void resumeOfAWellFormedCookieAnswers503AndKeepsTheIndicator()
            throws Exception {
        var browser = newBrowser();
        String csrf = browser.csrf();
        browser.cookies.put(
                RECONNECTION_COOKIE, UUID.randomUUID() + "." + AuthenticationSecrets.generate());
        HttpResponse<String> response;

        try (var _ = PoolHold.ofAll(dataSource)) {
            response = browser.post("resume", "", csrf);
        }

        assertThat(response.statusCode()).as("status").isEqualTo(503);
        assertThat(response.headers().allValues("set-cookie"))
                .as("cookies set by the answer")
                .noneMatch(header -> header.startsWith(RECONNECTABLE_COOKIE + "="));
    }

    @Test
    void logoutOfAWellFormedCookieAnswers503AndLeavesTheCookies()
            throws Exception {
        var browser = newBrowser();
        String csrf = browser.csrf();
        browser.cookies.put(
                RECONNECTION_COOKIE, UUID.randomUUID() + "." + AuthenticationSecrets.generate());
        HttpResponse<String> response;

        try (var _ = PoolHold.ofAll(dataSource)) {
            response = browser.post("logout", "", csrf);
        }

        assertThat(response.statusCode()).as("status").isEqualTo(503);
        assertThat(browser.cookies).as("cookies of the browser").containsKey(RECONNECTION_COOKIE);
    }
}
