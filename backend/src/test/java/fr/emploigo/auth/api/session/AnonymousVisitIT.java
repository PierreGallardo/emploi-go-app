package fr.emploigo.auth.api.session;

import static fr.emploigo.support.HostCookies.CSRF_COOKIE;
import static fr.emploigo.support.HostCookies.SESSION_COOKIE;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import fr.emploigo.auth.application.AuthenticationIntegrationTest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

/**
 * The CSRF token of a visitor without an account lives in a cookie, so bots and first loads cost no
 * row in PostgreSQL.
 */
class AnonymousVisitIT extends AuthenticationIntegrationTest {
    private static final String LOGIN =
            "{\"identifier\":\"missing\",\"password\":\"not a password\"}";

    static Stream<Arguments> anonymousRequests() {
        return Stream.of(
                argumentSet("cookie CSRF", "GET", "csrf", null),
                argumentSet("lecture de la session", "GET", "session", null),
                argumentSet("étape Google", "GET", "google/status", null),
                argumentSet("renvoi sans inscription", "POST", "signup/resend", "{}"),
                argumentSet(
                        "confirmation sans inscription",
                        "POST",
                        "signup/confirm",
                        "{\"code\":\"123456\"}"),
                argumentSet("reprise sans reconnexion", "POST", "resume", ""),
                argumentSet(
                        "demande de récupération",
                        "POST",
                        "password/forgot",
                        "{\"email\":\"absent@example.invalid\"}"),
                argumentSet("connexion refusée", "POST", "login", LOGIN));
    }

    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("anonymousRequests")
    void anonymousRequestCreatesNoSessionRowAndSetsNoSessionCookie(
            String method, String path, String body) throws Exception {
        var browser = newBrowser();
        String csrf = body == null ? null : browser.csrf();

        var response = browser.send(method, path, body, csrf);

        assertThat(count("spring_session")).as("session rows").isZero();
        assertThat(response.headers().allValues("set-cookie"))
                .as("set-cookie headers")
                .noneMatch(value -> value.startsWith(SESSION_COOKIE + "="));
        assertThat(browser.cookies).as("cookies of the browser").doesNotContainKey(SESSION_COOKIE);
    }

    @Test
    void postWithoutCsrfTokenIsRefusedWithoutCreatingASessionRow() throws Exception {
        var browser = newBrowser();

        var response = browser.send("POST", "login", LOGIN, null);

        assertThat(response.statusCode()).as("status").isEqualTo(403);
        assertThat(count("spring_session")).as("session rows").isZero();
        assertThat(browser.cookies).as("cookies of the browser").doesNotContainKey(SESSION_COOKIE);
    }

    @Test
    void csrfCookieIsSetWithTheExpectedAttributes() throws Exception {
        var browser = newBrowser();

        var response = browser.get("csrf");

        assertThat(response.statusCode()).as("status").isEqualTo(204);
        assertThat(response.headers().allValues("set-cookie"))
                .as("set-cookie headers")
                .singleElement()
                .satisfies(
                        value -> {
                            assertThat(value)
                                    .startsWith(CSRF_COOKIE + "=")
                                    .contains("Secure", "Path=/", "SameSite=Lax");
                            assertThat(value).doesNotContain("HttpOnly").doesNotContain("Domain");
                        });
    }

    @Test
    void firstResponseSetsTheCsrfCookieEvenWhenItIsA401() throws Exception {
        var browser = newBrowser();

        var response = browser.get("session");

        assertThat(response.statusCode()).as("status").isEqualTo(401);
        assertThat(response.headers().allValues("set-cookie"))
                .as("set-cookie headers")
                .singleElement()
                .asString()
                .startsWith(CSRF_COOKIE + "=");
    }

    @Test
    void csrfCookieIsNotWrittenAgainWhileTheBrowserHoldsIt() throws Exception {
        var browser = newBrowser();
        browser.get("csrf");
        String token = browser.cookies.get(CSRF_COOKIE);

        var response = browser.get("csrf");

        assertThat(response.headers().allValues("set-cookie")).as("set-cookie headers").isEmpty();
        assertThat(browser.cookies.get(CSRF_COOKIE)).as("token").isEqualTo(token);
    }
}
