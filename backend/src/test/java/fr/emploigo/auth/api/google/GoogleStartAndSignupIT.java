package fr.emploigo.auth.api.google;

import static fr.emploigo.support.HostCookies.RECONNECTION_COOKIE;
import static fr.emploigo.support.HostCookies.SESSION_COOKIE;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import fr.emploigo.support.FakeGoogle;
import fr.emploigo.support.MailCounters;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import java.util.stream.Stream;

class GoogleStartAndSignupIT extends GoogleAuthenticationSupport {
    static Stream<Arguments> browsersWithoutSession() {
        var unknownSession =
                Base64.getEncoder()
                        .encodeToString(
                                UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));
        return Stream.of(
                argumentSet("sans cookie", Map.<String, String>of()),
                argumentSet("cookie de session inconnu", Map.of(SESSION_COOKIE, unknownSession)));
    }

    @Test
    void startRequestsOpenIdAndEmailScopesWithS256PkceAndNonce() throws Exception {
        var browser = newBrowser();

        var flow = browser.start();

        assertThat(flow.parameters().get("scope").split(" "))
                .as("scopes")
                .containsExactlyInAnyOrder("openid", "email");
        assertThat(flow.parameters().get("code_challenge_method"))
                .as("PKCE method")
                .isEqualTo("S256");
        assertThat(flow.parameters()).as("authorization parameters").containsKey("nonce");
    }

    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("browsersWithoutSession")
    void startRefusedByTheAddressQuotaWritesNoSessionNorFlow(Map<String, String> cookies)
            throws Exception {
        IntStream.range(0, 30)
                .boxed()
                .map(unchecked(_ -> browserHolding(cookies).start()))
                .toList();
        long sessions = count("spring_session");
        long flows = count("auth_google_flows");

        var refused = browserHolding(cookies).post("google/start", Map.of());

        assertThat(refused.statusCode()).as("status").isEqualTo(429);
        assertThat(refused.headers().allValues("set-cookie"))
                .as("set-cookie headers")
                .noneMatch(value -> value.startsWith(SESSION_COOKIE + "="));
        assertThat(count("spring_session"))
                .as("session rows after the refused start")
                .isEqualTo(sessions);
        assertThat(count("auth_google_flows")).as("flows after the refused start").isEqualTo(flows);
    }

    @Test
    void startRefusedByTheBrowserQuotaKeepsOnlyTheSessionRowOfThatBrowser() throws Exception {
        var browser = newBrowser();
        assertThat(browser.postStatuses("google/start", Map.of(), 10))
                .as("statuses of the allowed starts")
                .containsOnly(200);
        assertThat(count("spring_session"))
                .as("session rows after the allowed starts")
                .isEqualTo(1);

        var refused = browser.post("google/start", Map.of());

        assertThat(refused.statusCode()).as("status").isEqualTo(429);
        assertThat(count("spring_session")).as("session rows after the refused start").isEqualTo(1);
    }

    @Test
    void callbackOfVerifiedGmailAsksForUsernameWithoutCreatingAnAccount() throws Exception {
        var browser = newBrowser();
        var flow = browser.start();

        var response = browser.callback(flow, false);

        assertThat(response.statusCode()).as("status").isEqualTo(302);
        assertThat(browser.stage()).as("stage").isEqualTo("CHOOSE_USERNAME");
        assertThat(count("auth_accounts")).as("accounts").isZero();
    }

    @Test
    void signupWithCsrfTokenIssuedBeforeTheCallbackAnswers403() throws Exception {
        var browser = newBrowser();
        var flow = browser.start();
        String csrfBeforeCallback = browser.csrf();
        accepted(browser, flow);

        var response =
                browser.post("google/signup", Map.of("username", "Jeune"), csrfBeforeCallback);

        assertThat(response.statusCode()).as("status").isEqualTo(403);
    }

    @Test
    void signupFromBrowserHoldingOnlyPreCallbackCookiesAnswers400() throws Exception {
        var stale = browserHoldingPreCallbackCookies();

        var response = stale.post("google/signup", Map.of("username", "Intrus"));

        assertThat(response.statusCode()).as("status").isEqualTo(400);
        assertThat(code(response)).as("error code").isEqualTo("INVALID_FLOW");
        assertThat(count("auth_accounts")).as("accounts").isZero();
    }

    @Test
    void statusFromBrowserHoldingOnlyPreCallbackCookiesIsNotPending() throws Exception {
        var stale = browserHoldingPreCallbackCookies();

        var status = stale.status();

        assertThat(status.path("pending").asBoolean()).as("pending").isFalse();
    }

    @Test
    void signupWithChosenUsernameCreatesPasswordlessAccountLinkedToTheSubject() throws Exception {
        var browser = pendingBrowser();
        var mails = new MailCounters();

        var response = browser.post("google/signup", Map.of("username", "Jeune"));

        assertThat(response.statusCode()).as("status").isEqualTo(200);
        assertThat(
                        jdbc.queryForList(
                                """
                                SELECT a.username FROM auth_accounts a JOIN auth_google_identities i
                                ON i.account_id = a.id WHERE i.subject = ?
                                """,
                                String.class,
                                FakeGoogle.subject))
                .as("usernames of the accounts linked to the subject")
                .containsExactly("jeune");
        assertThat(linkedAccountsWithoutPassword())
                .as("linked accounts have no local password")
                .containsExactly(true);
        assertThat(mails.calls()).as("provider calls").isZero();
    }

    @Test
    void signupWithAUsernameOutsideTheRuleAnswers422AndCreatesNoAccount() throws Exception {
        var browser = pendingBrowser();

        var response = browser.post("google/signup", Map.of("username", "Jeune Dijon"));

        assertThat(response.statusCode()).as("status").isEqualTo(422);
        assertThat(code(response)).as("error code").isEqualTo("INVALID_USERNAME");
        assertThat(count("auth_accounts")).as("accounts").isZero();
    }

    @Test
    void signupKeepsTheSessionWithTheReconnectionCookie() throws Exception {
        var browser = pendingBrowser();

        var response = browser.post("google/signup", Map.of("username", "Jeune"));

        assertThat(response.statusCode()).as("status").isEqualTo(200);
        assertThat(browser.cookies).as("cookies").containsKey(RECONNECTION_COOKIE);
    }

    @Test
    void callbackOfALinkedAccountKeepsTheSessionWithTheReconnectionCookie() throws Exception {
        signedUpWithGmail();
        var browser = newBrowser();
        var flow = browser.start();

        var response = browser.callback(flow, false);

        assertThat(response.statusCode()).as("status").isEqualTo(302);
        assertThat(browser.username()).as("session username").isEqualTo("jeune");
        assertThat(browser.cookies).as("cookies").containsKey(RECONNECTION_COOKIE);
    }

    @Test
    void replayOfTheConsumedCallbackCreatesNoSecondAccount() throws Exception {
        var signedUp = signedUpWithGmail();

        var replay = signedUp.browser().callback(signedUp.flow(), false);

        assertThat(replay.statusCode()).as("status").isEqualTo(302);
        assertThat(count("auth_accounts")).as("accounts").isEqualTo(1);
        assertThat(signedUp.browser().errorCode()).as("error code").isEqualTo("INVALID_FLOW");
    }

    @Test
    void signupWithExpiredPendingAnswers410AndCreatesNoAccount() throws Exception {
        var browser = pendingBrowser();
        clock.now = clock.now.plus(PAST_PROOF_DURATION);

        var response = browser.post("google/signup", Map.of("username", "Expired"));

        assertThat(response.statusCode()).as("status").isEqualTo(410);
        assertThat(count("auth_accounts")).as("accounts").isZero();
    }

    @Test
    void signupWithCurrentFlowAnswers200AndOpensSessionOfTheChosenUsername() throws Exception {
        var replaced = flowReplacedByAnotherTab();

        var response =
                replaced.browser()
                        .post(
                                "google/signup",
                                Map.of("flowId", replaced.newFlow(), "username", "Current"));

        assertThat(response.statusCode()).as("status").isEqualTo(200);
        assertThat(replaced.browser().username()).as("session username").isEqualTo("current");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"google/signup", "google/access", "google/email", "google/link"})
    void staleFlowIdAnswers400InvalidFlowAndChangesNothing(String path) throws Exception {
        var replaced = flowReplacedByAnotherTab();
        var mails = new MailCounters();

        var response =
                replaced.browser()
                        .post(
                                path,
                                Map.of(
                                        "flowId",
                                        replaced.oldFlow(),
                                        "username",
                                        "Stale",
                                        "identifier",
                                        "Stale",
                                        "password",
                                        password,
                                        "confirmed",
                                        true));

        assertThat(response.statusCode()).as("status").isEqualTo(400);
        assertThat(code(response)).as("error code").isEqualTo("INVALID_FLOW");
        assertThat(count("auth_accounts")).as("accounts").isZero();
        assertThat(mails.calls()).as("provider calls").isZero();
    }

    @Test
    void newStartInvalidatesThePendingProofOfThePreviousFlow() throws Exception {
        var browser = pendingBrowser();
        String previousFlow = browser.status().path("flowId").asText();
        var previous = sharingCookiesOf(browser);
        browser.start();

        var response =
                previous.post("google/signup", Map.of("flowId", previousFlow, "username", "New"));

        assertThat(response.statusCode()).as("status").isEqualTo(400);
        assertThat(code(response)).as("error code").isEqualTo("INVALID_FLOW");
    }
}
