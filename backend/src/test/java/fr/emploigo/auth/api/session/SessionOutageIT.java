package fr.emploigo.auth.api.session;

import static fr.emploigo.support.HostCookies.RECONNECTABLE_COOKIE;
import static fr.emploigo.support.HostCookies.RECONNECTION_COOKIE;

import static org.assertj.core.api.Assertions.assertThat;

import fr.emploigo.support.Containers;
import fr.emploigo.support.SqlFailure;

import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.util.UUID;

class SessionOutageIT extends AuthenticationSessionSupport {
    @Test
    void loginWhileRedisIsUnavailableAnswers503() throws Exception {
        var anonymous = newBrowser();
        String csrf = anonymous.csrf();

        try (var _ = Containers.pauseRedis()) {
            var response = login(anonymous, EMAIL, csrf);

            assertThat(response.statusCode()).as("status").isEqualTo(503);
        }
    }

    @Test
    void sessionReadWhileRedisIsUnavailableAnswers200() throws Exception {
        var browser = loggedIn();

        try (var _ = Containers.pauseRedis()) {
            var response = browser.get("session");

            assertThat(response.statusCode()).as("status").isEqualTo(200);
        }
    }

    @Test
    void logoutWhileRedisIsUnavailableAnswers204() throws Exception {
        var browser = loggedIn();
        String csrf = browser.csrf();

        try (var _ = Containers.pauseRedis()) {
            var response = browser.post("logout", "", csrf);

            assertThat(response.statusCode()).as("status").isEqualTo(204);
        }
    }

    @Test
    void resumeWhileRedisIsUnavailableAnswers200() throws Exception {
        var browser = loggedIn();
        expireTheSessionOfInactivity();

        try (var _ = Containers.pauseRedis()) {
            var response = resume(browser);

            assertThat(response.statusCode()).as("status").isEqualTo(200);
        }
    }

    @Test
    void resumeWithAWrongSecretWhileRedisIsUnavailableAnswersTheBodyOfAnUnknownFamily()
            throws Exception {
        var browser = loggedIn();
        String unknown = bodyOfTheRefusalOfAnUnknownFamily();
        UUID family =
                jdbc.queryForObject(
                        "SELECT id FROM auth_reconnections WHERE account_id = ?",
                        UUID.class,
                        accountId);
        browser.cookies.put(RECONNECTION_COOKIE, cookieWithAWrongSecret(family));

        try (var _ = Containers.pauseRedis()) {
            var response = resume(browser);

            assertThat(response.statusCode()).as("status").isEqualTo(401);
            assertThat(response.body()).as("body").isEqualTo(unknown);
        }
    }

    @Test
    void resumeFailingOnASqlOutageAnswers503AndKeepsTheReconnectableIndicator() throws Exception {
        var browser = loggedIn();
        String cookie = browser.cookies.get(RECONNECTION_COOKIE);

        HttpResponse<String> response;
        try (var _ = SqlFailure.inject(jdbc, "auth_reconnections", "BEFORE UPDATE")) {
            response = resume(browser);
        }

        assertThat(response.statusCode()).as("status").isEqualTo(503);
        assertThat(response.headers().firstValue("Retry-After"))
                .as("Retry-After of a database outage")
                .isEmpty();
        assertThat(browser.cookies)
                .as("cookies kept")
                .containsKeys(RECONNECTABLE_COOKIE)
                .containsEntry(RECONNECTION_COOKIE, cookie);
        assertThat(resume(browser).statusCode())
                .as("status of the resume once the outage is over")
                .isEqualTo(200);
    }
}
