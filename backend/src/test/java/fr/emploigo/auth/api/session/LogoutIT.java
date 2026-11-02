package fr.emploigo.auth.api.session;

import static fr.emploigo.support.HostCookies.RECONNECTABLE_COOKIE;
import static fr.emploigo.support.HostCookies.RECONNECTION_COOKIE;
import static fr.emploigo.support.HostCookies.SESSION_COOKIE;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import fr.emploigo.auth.application.failure.AuthenticationFailedException;
import fr.emploigo.auth.application.session.SessionPersistence;
import fr.emploigo.support.RowLock;
import fr.emploigo.support.SqlFailure;

import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

class LogoutIT extends AuthenticationSessionSupport {
    @Test
    void logoutAnswers204AndClearsBothCookies() throws Exception {
        var browser = loggedIn();

        var response = logout(browser);

        assertThat(response.statusCode()).as("status").isEqualTo(204);
        assertThat(browser.cookies)
                .as("cookies kept")
                .doesNotContainKeys(SESSION_COOKIE, RECONNECTION_COOKIE);
    }

    @Test
    void logoutClearsTheReconnectableIndicator() throws Exception {
        var browser = loggedIn();
        assertThat(browser.cookies).as("given: logged in").containsKey(RECONNECTABLE_COOKIE);

        logout(browser);

        assertThat(browser.cookies).as("cookies kept").doesNotContainKey(RECONNECTABLE_COOKIE);
    }

    @Test
    void logoutWithTheCsrfTokenIssuedBeforeLoginAnswers403() throws Exception {
        var browser = newBrowser();
        String before = browser.csrf();
        assertThat(login(browser, EMAIL, before).statusCode())
                .as("given: login accepted")
                .isEqualTo(200);

        var response = browser.post("logout", "", before);

        assertThat(response.statusCode()).as("status").isEqualTo(403);
    }

    @Test
    void loginAfterLogoutAnswers200WithTheNewCsrfTokenOfTheBrowser() throws Exception {
        var browser = loggedIn();
        String before = browser.csrf();
        logout(browser);

        // The logout deletes the CSRF cookie: the browser asks for a new one, as the frontend does.
        String next = browser.csrf();
        var response = login(browser, EMAIL, next);

        assertThat(next).as("CSRF token after logout").isNotEqualTo(before);
        assertThat(response.statusCode()).as("status").isEqualTo(200);
    }

    @Test
    void logoutDeletesTheSessionOfTheBrowser() throws Exception {
        var browser = loggedIn();

        logout(browser);

        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM spring_session WHERE principal_name = ?",
                                Long.class,
                                Long.toString(accountId)))
                .as("session rows of the account")
                .isZero();
    }

    @Test
    void logoutWithTheSessionCookieAloneRevokesTheFamilyOfTheSession() throws Exception {
        var browser = loggedIn();
        browser.cookies.remove(RECONNECTION_COOKIE);

        var response = logout(browser);

        assertThat(response.statusCode()).as("status").isEqualTo(204);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT revoked FROM auth_reconnections WHERE account_id = ?",
                                Boolean.class,
                                accountId))
                .as("family revoked")
                .isTrue();
    }

    @Test
    void logoutFailingOnASqlOutageAnswers503AndKeepsTheSessionAndItsCookies() throws Exception {
        var browser = loggedIn();
        var before = Map.copyOf(browser.cookies);

        HttpResponse<String> response;
        try (var _ = SqlFailure.inject(jdbc, "auth_reconnections", "BEFORE UPDATE")) {
            response = logout(browser);
        }

        assertThat(response.statusCode()).as("status").isEqualTo(503);
        assertThat(code(response)).as("code").isEqualTo("UNAVAILABLE");
        assertThat(browser.cookies).as("cookies kept").containsExactlyInAnyOrderEntriesOf(before);
        assertThat(browser.get("session").statusCode())
                .as("status of the session once the outage is over")
                .isEqualTo(200);
    }

    @Test
    void logoutFailingAfterTheRevocationAnswers503WithTheAccessesRevoked() throws Exception {
        var browser = loggedIn();
        var stolen = browser.copy();

        HttpResponse<String> response;
        try (var _ = SqlFailure.inject(jdbc, "spring_session", "BEFORE DELETE")) {
            response = logout(browser);
        }

        assertThat(response.statusCode()).as("status").isEqualTo(503);
        assertThat(stolen.get("session").statusCode())
                .as("status of the session captured before the logout")
                .isEqualTo(401);
    }

    @Test
    void sessionWithCookiesCapturedBeforeLogoutAnswers401() throws Exception {
        var browser = loggedIn();
        var stolen = browser.copy();
        assertThat(logout(browser).statusCode()).as("given: logout accepted").isEqualTo(204);

        var response = stolen.get("session");

        assertThat(response.statusCode()).as("status").isEqualTo(401);
        assertThat(code(response)).as("code").isEqualTo("UNAUTHENTICATED");
    }

    @Test
    void resumeWithCookiesCapturedBeforeLogoutAnswers401() throws Exception {
        var browser = loggedIn();
        var stolen = browser.copy();
        assertThat(logout(browser).statusCode()).as("given: logout accepted").isEqualTo(204);

        var response = resume(stolen);

        assertThat(response.statusCode()).as("status").isEqualTo(401);
    }

    @Test
    void revokingWithThePreviousCookieInvalidatesTheGrantOfTheRotation() throws Exception {
        String cookie = loggedIn().cookies.get(RECONNECTION_COOKIE);
        var account = resumeSession.resume(cookie).account();

        // The previous hash is accepted only to revoke, never to authenticate.
        sessions.revoke(cookie, null);

        assertThatThrownBy(
                        () ->
                                sessions.verify(
                                        account.id(),
                                        account.credentialVersion(),
                                        account.familyId()))
                .isInstanceOf(AuthenticationFailedException.class);
    }

    @Test
    void revocationOfThePreviousFamilyIsRolledBackWithTheCreationOfTheNewOne() throws Exception {
        String cookie = loggedIn().cookies.get(RECONNECTION_COOKIE);
        UUID family =
                jdbc.queryForObject(
                        "SELECT id FROM auth_reconnections WHERE account_id = ?",
                        UUID.class,
                        accountId);

        try (var _ = SqlFailure.inject(jdbc, "auth_reconnections", "BEFORE INSERT")) {
            assertThatThrownBy(
                            () ->
                                    sessions.create(
                                            accountId, SessionPersistence.REMEMBERED, cookie, family))
                    .as("creation")
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
        }

        assertThat(
                        jdbc.queryForObject(
                                "SELECT revoked FROM auth_reconnections WHERE id = ?",
                                Boolean.class,
                                family))
                .as("previous family revoked")
                .isFalse();
    }

    @Test
    void revokeWithTheSecretReadBeforeAConcurrentRotationStillRevokesTheFamily() throws Exception {
        String cookie = loggedIn().cookies.get(RECONNECTION_COOKIE);
        UUID family =
                jdbc.queryForObject(
                        "SELECT id FROM auth_reconnections WHERE account_id = ?",
                        UUID.class,
                        accountId);

        try (var pool = Executors.newVirtualThreadPerTaskExecutor();
                var lock = RowLock.hold(dataSource, "auth_reconnections", family)) {
            lock.execute(ROTATION);
            // The revoke reads the family as committed, then waits for the row held by the rotation
            // of the test.
            var revoking = pool.submit(() -> sessions.revoke(cookie, null));
            RowLock.awaitWaiters(jdbc, 1, ARRIVAL);
            lock.commit();
            revoking.get(ARRIVAL.toSeconds(), TimeUnit.SECONDS);
        }

        assertThat(
                        jdbc.queryForObject(
                                "SELECT revoked FROM auth_reconnections WHERE id = ?",
                                Boolean.class,
                                family))
                .as("family revoked")
                .isTrue();
    }

    @Test
    void logoutWithAWrongSecretAnswers204WithoutWaitingForTheLockOfTheFamily() throws Exception {
        UUID family = UUID.fromString(loggedIn().cookies.get(RECONNECTION_COOKIE).substring(0, 36));
        var anonymous = newBrowser();
        anonymous.cookies.put(RECONNECTION_COOKIE, cookieWithAWrongSecret(family));
        HttpResponse<String> response;

        try (var _ = RowLock.hold(dataSource, "auth_reconnections", family)) {
            response = assertTimeoutPreemptively(WITHOUT_WAITING, () -> logout(anonymous));
        }

        assertThat(response.statusCode()).as("status").isEqualTo(204);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT revoked FROM auth_reconnections WHERE id = ?",
                                Boolean.class,
                                family))
                .as("family revoked")
                .isFalse();
    }

    @Test
    void logoutWithTheReconnectionCookieAloneRevokesTheFamily() throws Exception {
        var browser = loggedIn();
        var anonymous = newBrowser();
        anonymous.cookies.put(RECONNECTION_COOKIE, browser.cookies.get(RECONNECTION_COOKIE));

        var response = logout(anonymous);

        assertThat(response.statusCode()).as("status").isEqualTo(204);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT revoked FROM auth_reconnections WHERE account_id = ?",
                                Boolean.class,
                                accountId))
                .as("family revoked")
                .isTrue();
    }
}
