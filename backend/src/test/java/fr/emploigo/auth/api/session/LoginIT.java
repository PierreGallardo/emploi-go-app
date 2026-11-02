package fr.emploigo.auth.api.session;

import static fr.emploigo.support.HostCookies.RECONNECTION_COOKIE;
import static fr.emploigo.support.HostCookies.SESSION_COOKIE;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.InstanceOfAssertFactories.STRING;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import com.github.kagkarlsson.scheduler.Scheduler;

import fr.emploigo.auth.application.session.AuthenticationSessions;
import fr.emploigo.auth.domain.Account;
import fr.emploigo.auth.domain.IdentifierTakenException;
import fr.emploigo.auth.domain.RegistrationPassword;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.stream.Stream;

class LoginIT extends AuthenticationSessionSupport {
    static Stream<Arguments> identifiers() {
        return Stream.of(
                argumentSet("email en majuscules", "JEUNE@example.invalid"),
                argumentSet("pseudo en majuscules", "ECOLE_DEMO"));
    }

    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("identifiers")
    void loginAnswers200RotatesTheSessionAndIssuesTheReconnectionCookie(String identifier)
            throws Exception {
        var browser = newBrowser();
        String csrf = browser.csrf();
        assertThat(browser.cookies)
                .as("given: an anonymous browser has no session")
                .doesNotContainKey(SESSION_COOKIE);

        var response = login(browser, identifier, csrf);

        assertThat(response.statusCode()).as("status").isEqualTo(200);
        assertThat(json.readTree(response.body()).path("username").asString())
                .as("username")
                .isEqualTo("ecole_demo");
        assertThat(browser.cookies).as("cookies").containsKey(SESSION_COOKIE);
        assertThat(response.headers().allValues("set-cookie"))
                .as("set-cookie headers")
                .anySatisfy(
                        value -> {
                            assertThat(value)
                                    .startsWith(RECONNECTION_COOKIE + "=")
                                    .contains("HttpOnly", "Secure", "SameSite=Lax");
                        });
        assertThat(response.headers().allValues("set-cookie"))
                .as("set-cookie headers")
                .anySatisfy(
                        value -> {
                            assertThat(value)
                                    .startsWith(SESSION_COOKIE + "=")
                                    .contains("HttpOnly", "Secure", "SameSite=Lax");
                        });
    }

    @Test
    void loginRecordsTheLastOpeningOfASessionOfTheAccount() throws Exception {
        var browser = newBrowser();

        login(browser, EMAIL, browser.csrf());

        assertThat(lastSeenAt()).as("last opening of a session")
                .isEqualTo(clock.instant());
    }

    @Test
    void requestOfAnOpenSessionLeavesTheLastOpeningOfASessionUnchanged() throws Exception {
        var browser = loggedIn();
        Instant opening = lastSeenAt();
        clock.now = clock.now.plus(Duration.ofMinutes(10));

        var response = browser.get("session");

        assertThat(response.statusCode()).as("status").isEqualTo(200);
        assertThat(lastSeenAt()).as("last opening of a session").isEqualTo(opening);
    }

    @Test
    void loginWithoutRememberMeGivesASessionThatEndsWithTheBrowser() throws Exception {
        var browser = newBrowser();

        var response = login(browser, EMAIL, browser.csrf(), false);

        assertThat(response.statusCode()).as("status").isEqualTo(200);
        assertThat(browser.cookies).as("cookies").doesNotContainKey(RECONNECTION_COOKIE);
        assertThat(response.headers().allValues("set-cookie"))
                .as("set-cookie headers")
                .filteredOn(value -> value.startsWith(SESSION_COOKIE + "="))
                .singleElement(STRING)
                .doesNotContainIgnoringCase("Max-Age")
                .doesNotContainIgnoringCase("Expires");
        assertThat(browser.get("session").statusCode()).as("session status").isEqualTo(200);
    }

    static Stream<Arguments> familyDurations() {
        return Stream.of(
                argumentSet("avec la case", true, AuthenticationSessions.RECONNECTION_DURATION),
                argumentSet("sans la case", false, AuthenticationSessions.SESSION_DURATION));
    }

    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("familyDurations")
    void loginKeepsTheFamilyOnlyAsLongAsWhatTheBrowserKeeps(boolean rememberMe, Duration kept)
            throws Exception {
        var browser = newBrowser();

        login(browser, EMAIL, browser.csrf(), rememberMe);

        assertThat(
                        jdbc.queryForObject(
                                        "SELECT expires_at FROM auth_reconnections"
                                                + " WHERE account_id = ?",
                                        Timestamp.class,
                                        accountId)
                                .toInstant())
                .as("expiry of the family")
                .isEqualTo(clock.instant().plus(kept));
    }

    @Test
    void loginWithoutRememberMeRevokesTheReconnectionOfTheLoginBeforeItInThisBrowser()
            throws Exception {
        var browser = loggedIn();
        var previous = newBrowser();
        previous.cookies.put(RECONNECTION_COOKIE, browser.cookies.get(RECONNECTION_COOKIE));
        assertThat(login(browser, EMAIL, browser.csrf(), false).statusCode())
                .as("given: login without the box accepted")
                .isEqualTo(200);

        var response = resume(previous);

        assertThat(response.statusCode()).as("status").isEqualTo(401);
        assertThat(code(response)).as("code").isEqualTo("RECONNECTION_REFUSED");
    }

    @Test
    void loginWithoutTheFieldOfTheBoxIssuesNoReconnectionCookie() throws Exception {
        var browser = newBrowser();

        var response =
                browser.post(
                        "login",
                        Map.of("identifier", EMAIL, "password", password),
                        browser.csrf());

        assertThat(response.statusCode()).as("status").isEqualTo(200);
        assertThat(browser.cookies).as("cookies").doesNotContainKey(RECONNECTION_COOKIE);
    }

    @Test
    void accountWithAPasswordOfTheFormerRuleStillLogsIn() throws Exception {
        String former = "abcdefghijkl";
        assertThat(RegistrationPassword.meetsRule(former))
                .as("given: password breaks the current rule")
                .isFalse();
        accounts.save(
                new Account(
                        "Ancienne_regle",
                        "ancienne@example.invalid",
                        passwords.encode(former),
                        true,
                        true));
        var browser = newBrowser();

        var response =
                browser.post(
                        "login",
                        Map.of("identifier", "ancienne@example.invalid", "password", former),
                        browser.csrf());

        assertThat(response.statusCode()).as("status").isEqualTo(200);
        assertThat(json.readTree(response.body()).path("username").asString())
                .as("username")
                .isEqualTo("ancienne_regle");
    }

    @Test
    void loginGivesTheSessionTheInactivityTimeoutOfTheConfiguration() throws Exception {
        loggedIn();

        var intervals =
                jdbc.queryForList("SELECT max_inactive_interval FROM spring_session", Long.class);

        assertThat(intervals)
                .as("inactivity timeouts of the sessions, in seconds")
                .containsOnly(sessionTimeout.toSeconds());
    }

    @Test
    void loginOfADisabledAccountAnswers401InvalidCredentials() throws Exception {
        jdbc.update("UPDATE auth_accounts SET enabled = FALSE WHERE id = ?", accountId);
        var candidate = newBrowser();

        var response = login(candidate, EMAIL, candidate.csrf());

        assertThat(response.statusCode()).as("status").isEqualTo(401);
        assertThat(code(response)).as("code").isEqualTo("INVALID_CREDENTIALS");
    }

    @Test
    void loginRecordsTheDateOfTheCurrentSecret() throws Exception {
        loggedIn();

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
    void databaseRejectsConcurrentAliasesEvenWithoutFrontendChecks() {
        assertThatThrownBy(
                        () ->
                                accounts.save(
                                        new Account(
                                                "ECOLE_DEMO",
                                                "another@example.invalid",
                                                passwords.encode(password),
                                                true,
                                                true)))
                .isInstanceOfSatisfying(
                        IdentifierTakenException.class,
                        error ->
                                assertThat(error.identifier())
                                        .isEqualTo(IdentifierTakenException.Identifier.USERNAME));
    }

    @Test
    void apiContextHasNoScheduler() {
        assertThat(context.getBeansOfType(Scheduler.class))
                .as("scheduler beans of the API")
                .isEmpty();
    }
}
