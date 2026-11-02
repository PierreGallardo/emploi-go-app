package fr.emploigo.auth.api.google;

import static fr.emploigo.support.HostCookies.RECONNECTION_COOKIE;
import static fr.emploigo.support.HostCookies.SESSION_COOKIE;

import static org.assertj.core.api.Assertions.assertThat;

import fr.emploigo.auth.domain.TestPasswords;
import fr.emploigo.support.FakeGoogle;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

class GoogleLocalLinkIT extends GoogleAuthenticationSupport {
    @Test
    void accessKeepsTheSessionWithTheReconnectionCookie() throws Exception {
        account("Local", FakeGoogle.email, true);
        var browser = newBrowser();
        accepted(browser, browser.start());

        var response =
                browser.post("google/access", Map.of("identifier", "local", "password", password));

        assertThat(response.statusCode()).as("status").isEqualTo(200);
        assertThat(browser.cookies).as("cookies").containsKey(RECONNECTION_COOKIE);
    }

    @Test
    void callbackWithEmailOfALocalAccountRequiresAccessAndLinksNothing() throws Exception {
        account("Local", FakeGoogle.email, true);
        var browser = newBrowser();
        var flow = browser.start();

        var response = browser.callback(flow, false);

        assertThat(response.statusCode()).as("status").isEqualTo(302);
        assertThat(browser.stage()).as("stage").isEqualTo("ACCOUNT_ACCESS_REQUIRED");
        assertThat(count("auth_google_identities")).as("linked identities").isZero();
    }

    @Test
    void signupWhenEmailBelongsToALocalAccountAnswers409() throws Exception {
        account("Local", FakeGoogle.email, true);
        var browser = pendingBrowser();

        var response = browser.post("google/signup", Map.of("username", "Another"));

        assertThat(response.statusCode()).as("status").isEqualTo(409);
    }

    @Test
    void linkBeforeVerifyingThePasswordAnswers409() throws Exception {
        account("Local", FakeGoogle.email, true);
        var browser = pendingBrowser();

        var response = confirmLink(browser);

        assertThat(response.statusCode()).as("status").isEqualTo(409);
    }

    @Test
    void accessWithWrongPasswordAnswers401() throws Exception {
        account("Local", FakeGoogle.email, true);
        var browser = pendingBrowser();

        var response =
                browser.post("google/access", Map.of("identifier", "local", "password", "wrong"));

        assertThat(response.statusCode()).as("status").isEqualTo(401);
    }

    @Test
    void accessAtTheLimitIsRefusedKeepsTheStageAndSucceedsOnceFree() throws Exception {
        account("Local", FakeGoogle.email, true);
        var browser = pendingBrowser();

        try (var gate = hashingGate()) {
            gate.saturate(hashing.limit());

            var refused =
                    browser.post(
                            "google/access", Map.of("identifier", "local", "password", password));

            assertThat(refused.statusCode()).as("status").isEqualTo(503);
            assertThat(refused.headers().firstValue("Retry-After")).as("Retry-After").contains("5");
            assertThat(code(refused)).as("code").isEqualTo("BUSY");
            assertThat(browser.stage()).as("stage").isEqualTo("ACCOUNT_ACCESS_REQUIRED");

            gate.releaseAndWait();
            var accepted =
                    browser.post(
                            "google/access", Map.of("identifier", "local", "password", password));

            assertThat(accepted.statusCode()).as("status once free").isEqualTo(200);
            assertThat(browser.stage()).as("stage once free").isEqualTo("CONFIRM_LINK");
        }
    }

    @Test
    void accessSharesTheDelayOfTheLoginSoTheCorrectPasswordIsRefusedAfterFiveFailures()
            throws Exception {
        account("Local", FakeGoogle.email, true);
        var browser = pendingBrowser();
        var failures =
                IntStream.range(0, 5)
                        .boxed()
                        .map(
                                unchecked(
                                        i ->
                                                browser.post(
                                                                "login",
                                                                Map.of(
                                                                        "identifier",
                                                                        "local",
                                                                        "password",
                                                                        "wrong"))
                                                        .statusCode()))
                        .toList();
        assertThat(failures).as("given: five failures of the login").containsOnly(401);

        var refused =
                browser.post("google/access", Map.of("identifier", "local", "password", password));

        assertThat(refused.statusCode()).as("status").isEqualTo(429);
        assertThat(refused.headers().firstValue("Retry-After").map(Integer::parseInt))
                .as("Retry-After, in seconds")
                .hasValueSatisfying(wait -> assertThat(wait).isBetween(1, 5));
        assertThat(browser.stage()).as("stage").isEqualTo("ACCOUNT_ACCESS_REQUIRED");
    }

    @Test
    void accessSucceedingForgetsTheFailuresOfTheLogin() throws Exception {
        account("Local", FakeGoogle.email, true);
        var browser = pendingBrowser();
        browser.post("google/access", Map.of("identifier", "local", "password", "wrong"));

        var accepted =
                browser.post("google/access", Map.of("identifier", "local", "password", password));

        assertThat(accepted.statusCode()).as("status").isEqualTo(200);
        assertThat(redis.keys("auth:limits:login:failures:*")).as("failure counters").isEmpty();
    }

    @Test
    void accessWithLocalPasswordMovesToConfirmLink() throws Exception {
        account("Local", FakeGoogle.email, true);
        var browser = pendingBrowser();

        var response =
                browser.post("google/access", Map.of("identifier", "local", "password", password));

        assertThat(response.statusCode()).as("status").isEqualTo(200);
        assertThat(browser.stage()).as("stage").isEqualTo("CONFIRM_LINK");
    }

    @Test
    void linkWithoutConfirmationAnswers400() throws Exception {
        account("Local", FakeGoogle.email, true);
        var browser = accessVerifiedBrowser();

        var response = browser.post("google/link", Map.of("confirmed", false));

        assertThat(response.statusCode()).as("status").isEqualTo(400);
    }

    @Test
    void linkedSubjectWithAnotherEmailLogsInTheSameAccountWithoutCreatingOne() throws Exception {
        var signedUp = signedUpWithGmail();
        var browser = signedUp.browser();
        assertThat(browser.post("logout", Map.of()).statusCode())
                .as("given: logout accepted")
                .isEqualTo(204);
        FakeGoogle.email = "changed@external.invalid";

        var response = browser.callback(browser.start(), false);

        assertThat(response.statusCode()).as("status").isEqualTo(302);
        assertThat(response.headers().firstValue("Location").orElse(""))
                .as("redirect")
                .endsWith("/")
                .doesNotContain("/carte");
        assertThat(browser.get("session").statusCode()).as("session status").isEqualTo(200);
        assertThat(linkedAccount())
                .as("account linked to the subject")
                .isEqualTo(signedUp.accountId());
        assertThat(count("auth_accounts")).as("accounts").isEqualTo(1);
    }

    @Test
    void resumeAfterLinkedGoogleCallbackRestoresTheSession() throws Exception {
        var browser = signedUpWithGmail().browser();
        assertThat(browser.post("logout", Map.of()).statusCode())
                .as("given: logout accepted")
                .isEqualTo(204);
        FakeGoogle.email = "changed@external.invalid";
        assertThat(browser.callback(browser.start(), false).statusCode())
                .as("given: callback accepted")
                .isEqualTo(302);
        browser.cookies.remove(SESSION_COOKIE);

        var response = browser.post("resume", Map.of());

        assertThat(response.statusCode()).as("status").isEqualTo(200);
    }

    @Test
    void recoveryRequestForGoogleOnlyAccountAnswers202AndCreatesNoReset() throws Exception {
        long id = signedUpWithGmail().accountId();

        var response = newBrowser().post("password/forgot", Map.of("email", FakeGoogle.email));

        assertThat(response.statusCode()).as("status").isEqualTo(202);
        assertThat(count("auth_password_resets")).as("password resets").isZero();
        assertThat(passwordless(id)).as("account is still passwordless").isTrue();
    }

    @Test
    void linkAfterPasswordResetOfTheAccountAnswers400AndLinksNothing() throws Exception {
        long local = account("Local", FakeGoogle.email, true);
        var browser = accessVerifiedBrowser();
        var recovery = newBrowser();
        var forgot = recovery.post("password/forgot", Map.of("email", FakeGoogle.email));
        assertThat(forgot.statusCode()).as("given: recovery requested").isEqualTo(202);
        UUID resetId =
                jdbc.queryForObject(
                        "SELECT id FROM auth_password_resets WHERE account_id = ?",
                        UUID.class,
                        local);
        String replacement = TestPasswords.random();
        var reset =
                recovery.post(
                        "password/reset",
                        Map.of(
                                "token",
                                resetId + "." + tokens.recoveryToken(resetId),
                                "password",
                                replacement,
                                "confirmation",
                                replacement));
        assertThat(reset.statusCode()).as("given: reset accepted").isEqualTo(200);

        var response = confirmLink(browser);

        assertThat(response.statusCode()).as("status").isEqualTo(400);
        assertThat(count("auth_google_identities")).as("linked identities").isZero();
    }

    @Test
    void linkKeepsTheSessionOfAnotherBrowser() throws Exception {
        account("Local", FakeGoogle.email, true);
        var other = loggedInLocalBrowser();
        var retained = sharingCookiesOf(other);
        linkedWhileProviderDown(accessVerifiedBrowser());

        var response = retained.get("session");

        assertThat(response.statusCode()).as("status").isEqualTo(200);
    }

    @Test
    void linkKeepsTheReconnectionOfAnotherBrowser() throws Exception {
        account("Local", FakeGoogle.email, true);
        var other = loggedInLocalBrowser();
        var retained = sharingCookiesOf(other);
        linkedWhileProviderDown(accessVerifiedBrowser());
        retained.cookies.remove(SESSION_COOKIE);

        var response = retained.post("resume", Map.of());

        assertThat(response.statusCode()).as("status").isEqualTo(200);
    }

    @Test
    void linkKeepsTheReconnectionOfTheLinkingBrowser() throws Exception {
        account("Local", FakeGoogle.email, true);
        var browser = accessVerifiedBrowser();
        linkedWhileProviderDown(browser);
        browser.cookies.remove(SESSION_COOKIE);

        var response = browser.post("resume", Map.of());

        assertThat(response.statusCode()).as("status").isEqualTo(200);
    }

    @Test
    void secondLinkAfterTheLinkCompletedAnswers400InvalidFlow() throws Exception {
        account("Local", FakeGoogle.email, true);
        var browser = accessVerifiedBrowser();
        linkedWhileProviderDown(browser);

        var response = confirmLink(browser);

        assertThat(response.statusCode()).as("status").isEqualTo(400);
        assertThat(code(response)).as("error code").isEqualTo("INVALID_FLOW");
        assertThat(count("auth_google_identities")).as("linked identities").isEqualTo(1);
    }
}
