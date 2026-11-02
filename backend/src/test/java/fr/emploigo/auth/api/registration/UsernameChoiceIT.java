package fr.emploigo.auth.api.registration;

import static fr.emploigo.support.HostCookies.RECONNECTION_COOKIE;

import static org.assertj.core.api.Assertions.assertThat;

import fr.emploigo.support.Browser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

class UsernameChoiceIT extends RegistrationTestBase {
    /** A browser whose registration of {@code email} is confirmed: its account has no username. */
    private Browser confirmed(String email) throws Exception {
        return confirmed(email, false);
    }

    /** A browser confirmed after a signup with the box « Rester connecté » as given. */
    private Browser confirmed(String email, boolean rememberMe) throws Exception {
        var browser = newBrowser();
        String code = pendingSignup(browser, email, rememberMe);
        assertThat(confirm(browser, code).statusCode()).as("given: code accepted").isEqualTo(200);
        return browser;
    }

    private HttpResponse<String> choose(Browser browser, String username) throws Exception {
        return browser.post("username", Map.of("username", username));
    }

    private String sessionAccount(Browser browser) throws Exception {
        var session = browser.get("session");
        assertThat(session.statusCode()).as("session status").isEqualTo(200);
        return json.readTree(session.body()).path("accountId").asString();
    }

    @Test
    void twoAccountsWithoutUsernameHaveDistinctAccountIdentifiers() throws Exception {
        var first = confirmed(DEMO_EMAIL);
        var second = confirmed(NEW_EMAIL);

        var accounts = List.of(sessionAccount(first), sessionAccount(second));

        assertThat(accounts).as("account identifiers").doesNotContain("").doesNotHaveDuplicates();
    }

    @Test
    void choiceKeepsTheAccountIdentifierOfTheSession() throws Exception {
        var browser = confirmed(DEMO_EMAIL);
        String before = sessionAccount(browser);

        var response = choose(browser, "Jeune_demo");

        assertThat(json.readTree(response.body()).path("accountId").asString())
                .as("account identifier of the answer")
                .isEqualTo(before);
    }

    @Test
    void chosenUsernameIsStoredInLowerCaseAndLogsInWhateverTheCase() throws Exception {
        var browser = confirmed(DEMO_EMAIL);

        var response = choose(browser, "Jeune_demo");

        assertThat(response.statusCode()).as("status").isEqualTo(200);
        assertThat(sessionUsername(browser)).as("username of the session").isEqualTo("jeune_demo");
        assertThat(login(newBrowser(), "JEUNE_DEMO").statusCode())
                .as("login with the username in upper case")
                .isEqualTo(200);
    }

    @ParameterizedTest(name = "rester connecté : {0}")
    @ValueSource(booleans = {true, false})
    void choiceKeepsTheSessionAsTheBoxOfTheSignupChose(boolean rememberMe) throws Exception {
        var browser = confirmed(DEMO_EMAIL, rememberMe);

        var response = choose(browser, "Jeune_demo");

        assertThat(response.statusCode()).as("status").isEqualTo(200);
        assertThat(browser.cookies.containsKey(RECONNECTION_COOKIE))
                .as("reconnection cookie kept")
                .isEqualTo(rememberMe);
    }

    @Test
    void choiceReplacesTheSessionOfTheCode() throws Exception {
        var browser = confirmed(DEMO_EMAIL);
        var before = browser.copy();

        assertThat(choose(browser, "Jeune_demo").statusCode())
                .as("given: choice accepted")
                .isEqualTo(200);

        assertThat(before.get("session").statusCode())
                .as("status of the session of the code")
                .isEqualTo(401);
    }

    @Test
    void usernameOfAnotherAccountAnswers409AndLeavesTheAccountWithoutUsername() throws Exception {
        existingAccount();
        var browser = confirmed(DEMO_EMAIL);

        var response = choose(browser, TAKEN_USERNAME.toUpperCase());

        assertThat(response.statusCode()).as("status").isEqualTo(409);
        assertThat(code(response)).as("error code").isEqualTo("USERNAME_UNAVAILABLE");
        assertThat(sessionUsername(browser)).as("username of the session").isNull();
    }

    @Test
    void secondChoiceAnswers409AlreadyChosenAndKeepsTheFirst() throws Exception {
        var browser = confirmed(DEMO_EMAIL);
        assertThat(choose(browser, "Premier").statusCode())
                .as("given: first choice")
                .isEqualTo(200);

        var response = choose(browser, "Second");

        assertThat(response.statusCode()).as("status").isEqualTo(409);
        assertThat(code(response)).as("error code").isEqualTo("USERNAME_ALREADY_CHOSEN");
        assertThat(sessionUsername(browser)).as("username of the session").isEqualTo("premier");
    }

    @Test
    void choiceWithoutSessionAnswers401() throws Exception {
        var response = choose(newBrowser(), "Jeune_demo");

        assertThat(response.statusCode()).as("status").isEqualTo(401);
    }

    @Test
    void sameUsernameChosenAtOnceByTwoAccountsGoesToOneOfThem() throws Exception {
        var first = confirmed(DEMO_EMAIL);
        var second = confirmed(NEW_EMAIL);

        List<Integer> statuses;
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var one = pool.submit(() -> choose(first, "Dispute").statusCode());
            var other = pool.submit(() -> choose(second, "Dispute").statusCode());
            statuses = List.of(one.get(10, TimeUnit.SECONDS), other.get(10, TimeUnit.SECONDS));
        }

        assertThat(statuses).as("statuses").containsExactlyInAnyOrder(200, 409);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM auth_accounts WHERE username = 'dispute'",
                                Long.class))
                .as("accounts with the username")
                .isEqualTo(1);
    }

    @ParameterizedTest(name = "« {0} »")
    @ValueSource(strings = {" ", "jeune-demo"})
    void usernameOutsideTheRuleAnswers422AndLeavesTheAccountWithoutUsername(String username)
            throws Exception {
        var browser = confirmed(DEMO_EMAIL);

        var response = choose(browser, username);

        assertThat(response.statusCode()).as("status").isEqualTo(422);
        assertThat(code(response)).as("error code").isEqualTo("INVALID_USERNAME");
        assertThat(sessionUsername(browser)).as("username of the session").isNull();
    }

    @Test
    void severalAccountsStayWithoutUsername() throws Exception {
        confirmed(DEMO_EMAIL);

        confirmed(NEW_EMAIL);

        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM auth_accounts WHERE username IS NULL",
                                Long.class))
                .as("accounts without username")
                .isEqualTo(2);
    }

    @Test
    void accountWithoutUsernameLogsInWithItsEmail() throws Exception {
        confirmed(NEW_EMAIL);
        var browser = newBrowser();

        var response = login(browser, NEW_EMAIL);

        assertThat(response.statusCode()).as("status").isEqualTo(200);
        assertThat(sessionUsername(browser)).as("username of the session").isNull();
    }
}
