package fr.emploigo.auth.api.session;

import static fr.emploigo.support.HostCookies.RECONNECTABLE_COOKIE;
import static fr.emploigo.support.HostCookies.RECONNECTION_COOKIE;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import fr.emploigo.support.RowLock;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.UUID;
import java.util.stream.Stream;

class ResumeIT extends AuthenticationSessionSupport {
    @Test
    void resumeRenewsTheReconnectableIndicator() throws Exception {
        var browser = loggedIn();
        expireTheSessionOfInactivity();

        var response = resume(browser);

        assertThat(response.statusCode()).as("status").isEqualTo(200);
        assertThat(indicatorOf(response))
                .as("indicator")
                .startsWith(RECONNECTABLE_COOKIE + "=1;")
                .contains(RECONNECTION_SECONDS);
    }

    @Test
    void resumeAnswersTheAccountIdentifierOfTheLogin() throws Exception {
        var browser = loggedIn();
        String before = json.readTree(browser.get("session").body()).path("accountId").asString();
        expireTheSessionOfInactivity();

        var response = resume(browser);

        assertThat(json.readTree(response.body()).path("accountId").asString())
                .as("account identifier")
                .isEqualTo(before);
    }

    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("accountChangesThatRefuseTheReconnection")
    void resumeOfAnAccountThatLostItsAccessClearsTheReconnectableIndicator(String change)
            throws Exception {
        var browser = loggedIn();
        jdbc.update(
                """
                UPDATE auth_accounts SET %s WHERE id = ?
                """
                        .formatted(change),
                accountId);

        var response = resume(browser);

        assertThat(response.statusCode()).as("status").isEqualTo(401);
        assertThat(code(response)).as("code").isEqualTo("RECONNECTION_REFUSED");
        assertThat(browser.cookies).as("cookies kept").doesNotContainKey(RECONNECTABLE_COOKIE);
    }

    static Stream<Arguments> rowsThatResumeLocks() {
        // The column that ties the row to the account of the test.
        return Stream.of(
                argumentSet("compte", "auth_accounts", "id"),
                argumentSet("famille de reconnexion", "auth_reconnections", "account_id"));
    }

    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("rowsThatResumeLocks")
    void resumeWithAWrongSecretAnswers401WithoutWaitingForTheLockOfAnotherTransaction(
            String table, String accountColumn) throws Exception {
        var browser = loggedIn();
        UUID family =
                jdbc.queryForObject(
                        "SELECT id FROM auth_reconnections WHERE account_id = ?",
                        UUID.class,
                        accountId);
        browser.cookies.put(RECONNECTION_COOKIE, cookieWithAWrongSecret(family));
        Object id =
                jdbc.queryForObject(
                        """
                        SELECT id FROM %s WHERE %s = ?
                        """
                                .formatted(table, accountColumn),
                        Object.class,
                        accountId);
        HttpResponse<String> response;

        try (var _ = RowLock.hold(dataSource, table, id)) {
            response = assertTimeoutPreemptively(WITHOUT_WAITING, () -> resume(browser));
        }

        assertThat(response.statusCode()).as("status").isEqualTo(401);
    }

    @Test
    void resumeAfterInactivityAnswers200AndRotatesTheReconnectionCookie() throws Exception {
        var browser = loggedIn();
        String first = browser.cookies.get(RECONNECTION_COOKIE);
        expireTheSessionOfInactivity();

        var response = resume(browser);

        assertThat(response.statusCode()).as("status").isEqualTo(200);
        assertThat(browser.cookies.get(RECONNECTION_COOKIE))
                .as("reconnection cookie")
                .isNotEqualTo(first);
    }

    @Test
    void resumeAfterTheAbsoluteLifetimeAnswers200AndRenewsTheReconnectionForThirtyDays()
            throws Exception {
        var browser = loggedIn();
        clock.now = clock.now.plus(Duration.ofHours(8));

        var response = resume(browser);

        assertThat(response.statusCode()).as("status").isEqualTo(200);
        var expiry =
                jdbc.queryForObject(
                                "SELECT expires_at FROM auth_reconnections WHERE account_id = ?",
                                Timestamp.class,
                                accountId)
                        .toInstant();
        assertThat(expiry)
                .as("reconnection expiry")
                .isEqualTo(clock.instant().plus(Duration.ofDays(30)));
    }

    @Test
    void resumeAtTheExpiryOfTheReconnectionAnswers401() throws Exception {
        var browser = loggedIn();
        clock.now =
                jdbc.queryForObject(
                                "SELECT expires_at FROM auth_reconnections WHERE account_id = ?",
                                Timestamp.class,
                                accountId)
                        .toInstant();

        var response = resume(browser);

        assertThat(response.statusCode()).as("status").isEqualTo(401);
    }

    @Test
    void resumeRecordsTheDateOfTheCurrentSecret() throws Exception {
        var browser = loggedIn();
        clock.now = clock.now.plus(Duration.ofDays(3));

        resume(browser);

        assertThat(
                        jdbc.queryForObject(
                                        """
                                            SELECT rotated_at FROM auth_reconnections
                                            WHERE account_id = ?
                                            """,
                                        Timestamp.class,
                                        accountId)
                                .toInstant())
                .as("date of the secret")
                .isEqualTo(clock.instant());
    }

    @Test
    void resumeRecordsTheLastOpeningOfASessionOfTheAccount() throws Exception {
        var browser = loggedIn();
        clock.now = clock.now.plus(Duration.ofDays(3));

        resume(browser);

        assertThat(lastSeenAt()).as("last opening of a session")
                .isEqualTo(clock.instant());
    }

    @Test
    void resumeRefusedByTheRuleAnswersTheBodyOfAnUnknownFamily() throws Exception {
        var browser = loggedIn();
        String unknown = bodyOfTheRefusalOfAnUnknownFamily();
        jdbc.update(ROTATION + ", rotated_at = rotated_at - INTERVAL '1 minute'");

        var response = resume(browser);

        assertThat(response.statusCode()).as("status").isEqualTo(401);
        assertThat(response.body()).as("body").isEqualTo(unknown);
    }

    @Test
    void resumeWithAWrongSecretAnswersTheBodyOfAnUnknownFamily() throws Exception {
        var browser = loggedIn();
        String unknown = bodyOfTheRefusalOfAnUnknownFamily();
        UUID family =
                jdbc.queryForObject(
                        "SELECT id FROM auth_reconnections WHERE account_id = ?",
                        UUID.class,
                        accountId);
        browser.cookies.put(RECONNECTION_COOKIE, cookieWithAWrongSecret(family));

        var response = resume(browser);

        assertThat(response.statusCode()).as("status").isEqualTo(401);
        assertThat(response.body()).as("body").isEqualTo(unknown);
    }
}
