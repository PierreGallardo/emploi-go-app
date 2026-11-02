package fr.emploigo.auth.api.google;

import static fr.emploigo.support.FakeGoogle.TOKEN_CALLS;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import fr.emploigo.auth.api.GoogleBrowser.Flow;
import fr.emploigo.support.FakeGoogle;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

class GoogleCallbackSecurityIT extends GoogleAuthenticationSupport {
    static Stream<Arguments> accountsThatMayNotLogIn() {
        return Stream.of(
                argumentSet(
                        "compte suspendu", "UPDATE auth_accounts SET enabled = FALSE WHERE id = ?"),
                argumentSet(
                        "compte administrateur",
                        "UPDATE auth_accounts SET role = 'ADMIN' WHERE id = ?"));
    }

    @Test
    void idTokenWithAnotherNonceIsRefusedAsProviderRejected() throws Exception {
        FakeGoogle.mode = "NONCE";
        var browser = newBrowser();
        var flow = browser.start();
        var tokenCalls = new Calls(TOKEN_CALLS);

        var response = browser.callback(flow, false);

        assertThat(response.statusCode()).as("status").isEqualTo(302);
        assertThat(browser.errorCode()).as("error code").isEqualTo("PROVIDER_REJECTED");
        assertThat(browser.get("session").statusCode()).as("session status").isEqualTo(401);
        assertThat(tokenCalls.count())
                .as("token endpoint calls: the token itself was refused")
                .isEqualTo(1);
    }

    @Test
    void callbackWithForgedStateIsRefusedWithoutCallingTheTokenEndpoint() throws Exception {
        var browser = newBrowser();
        var flow = browser.start();
        var forged = new HashMap<>(flow.parameters());
        forged.put("state", "forged");
        var tokenCalls = new Calls(TOKEN_CALLS);

        var response = browser.callback(new Flow(forged), false);

        assertThat(response.statusCode()).as("status").isEqualTo(302);
        assertThat(browser.errorCode()).as("error code").isEqualTo("INVALID_FLOW");
        assertThat(tokenCalls.count()).as("token endpoint calls").isZero();
    }

    @Test
    void callbackWithoutAFlowRedirectsWithoutCreatingASession() throws Exception {
        var browser = newBrowser();

        var response = browser.get("google/callback/google?state=forged&code=forged");

        assertThat(response.statusCode()).as("status").isEqualTo(302);
        assertThat(response.headers().firstValue("location").orElse(""))
                .as("location")
                .endsWith("/connexion-google");
        assertThat(count("spring_session")).as("session rows").isZero();
        assertThat(browser.errorCode()).as("error code: nothing to remember it in").isEmpty();
    }

    @Test
    void callbackWithWrongPkceChallengeIsRefusedAsProviderRejected() throws Exception {
        var browser = newBrowser();
        var flow = browser.start();
        var broken = new HashMap<>(flow.parameters());
        broken.put("code_challenge", "wrong");

        var response = browser.callback(new Flow(broken), false);

        assertThat(response.statusCode()).as("status").isEqualTo(302);
        assertThat(browser.errorCode()).as("error code").isEqualTo("PROVIDER_REJECTED");
        assertThat(browser.pending()).as("pending").isFalse();
    }

    @Test
    void cancelledCallbackReportsCancelledWithoutCallingTheTokenEndpoint() throws Exception {
        var browser = newBrowser();
        var flow = browser.start();
        var tokenCalls = new Calls(TOKEN_CALLS);

        var response = browser.callback(flow, true);

        assertThat(response.statusCode()).as("status").isEqualTo(302);
        assertThat(browser.errorCode()).as("error code").isEqualTo("CANCELLED");
        assertThat(tokenCalls.count()).as("token endpoint calls").isZero();
    }

    @Test
    void callbackOfAnExpiredAttemptCallsNoTokenEndpoint() throws Exception {
        var browser = newBrowser();
        var flow = browser.start();
        clock.now = clock.now.plus(PAST_PROOF_DURATION);
        var tokenCalls = new Calls(TOKEN_CALLS);

        var response = browser.callback(flow, false);

        assertThat(response.statusCode()).as("status").isEqualTo(302);
        assertThat(browser.errorCode()).as("error code").isEqualTo("INVALID_FLOW");
        assertThat(tokenCalls.count()).as("token endpoint calls").isZero();
    }

    @Test
    void callbackAfterLoginInTheSameBrowserCallsNoTokenEndpoint() throws Exception {
        var browser = newBrowser();
        var flow = browser.start();
        account("Local", FakeGoogle.email, true);
        var login = browser.post("login", Map.of("identifier", "Local", "password", password));
        assertThat(login.statusCode()).as("given: login accepted").isEqualTo(200);
        var tokenCalls = new Calls(TOKEN_CALLS);

        var response = browser.callback(flow, false);

        assertThat(response.statusCode()).as("status").isEqualTo(302);
        assertThat(tokenCalls.count()).as("token endpoint calls").isZero();
    }

    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("accountsThatMayNotLogIn")
    void callbackOfLinkedAccountThatMayNotLogInOpensNoSession(String restriction) throws Exception {
        long id = linkedGoogleOwner("Blocked");
        jdbc.update(restriction, id);
        var browser = newBrowser();
        var flow = browser.start();

        var response = browser.callback(flow, false);

        assertThat(response.statusCode()).as("status").isEqualTo(302);
        assertThat(browser.errorCode()).as("error code").isEqualTo("PROVIDER_REJECTED");
        assertThat(browser.get("session").statusCode()).as("session status").isEqualTo(401);
    }
}
