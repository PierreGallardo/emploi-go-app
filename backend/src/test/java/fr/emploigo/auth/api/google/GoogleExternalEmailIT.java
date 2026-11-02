package fr.emploigo.auth.api.google;

import static org.assertj.core.api.Assertions.assertThat;

import fr.emploigo.support.FakeGoogle;
import fr.emploigo.support.MailCounters;

import org.junit.jupiter.api.Test;

import java.util.Map;

class GoogleExternalEmailIT extends GoogleAuthenticationSupport {
    @Test
    void callbackWithExternalEmailRequiresEmailConfirmation() throws Exception {
        FakeGoogle.email = EXTERNAL_EMAIL;
        var browser = newBrowser();
        var flow = browser.start();

        var response = browser.callback(flow, false);

        assertThat(response.statusCode()).as("status").isEqualTo(302);
        assertThat(browser.stage()).as("stage").isEqualTo("EMAIL_CONFIRMATION_REQUIRED");
    }

    @Test
    void signupBeforeEmailConfirmationAnswers400() throws Exception {
        var browser = pendingExternalBrowser();

        var response = browser.post("google/signup", Map.of("username", "External"));

        assertThat(response.statusCode()).as("status").isEqualTo(400);
        assertThat(count("auth_accounts")).as("accounts").isZero();
    }

    @Test
    void secondEmailRequestWithinTheSendDelayAnswers429AndCallsNoProvider() throws Exception {
        var browser = pendingExternalBrowser();
        assertThat(browser.post("google/email", Map.of()).statusCode())
                .as("given: first email requested")
                .isEqualTo(202);
        var mails = new MailCounters();

        var response = browser.post("google/email", Map.of());

        assertThat(response.statusCode()).as("status").isEqualTo(429);
        assertThat(code(response)).as("error code").isEqualTo("MAIL_RECENTLY_SENT");
        assertThat(response.headers().firstValue("Retry-After").map(Integer::parseInt))
                .as("Retry-After, in seconds")
                .hasValueSatisfying(wait -> assertThat(wait).isBetween(1, 60));
        assertThat(mails.calls()).as("provider calls").isZero();
    }

    @Test
    void emailRequestAfterTheSendDelayAnswers202AndSendsAnotherMail() throws Exception {
        var browser = pendingExternalBrowser();
        assertThat(browser.post("google/email", Map.of()).statusCode())
                .as("given: first email requested")
                .isEqualTo(202);
        permitNextMail(FakeGoogle.email);
        var mails = new MailCounters();

        var response = browser.post("google/email", Map.of());

        assertThat(response.statusCode()).as("status").isEqualTo(202);
        assertThat(mails.calls()).as("provider calls").isEqualTo(1);
        assertThat(pendingVerifications()).as("pending verifications").isEqualTo(1);
    }

    @Test
    void getOnEmailConfirmAnswers401() throws Exception {
        requestedEmailLink(pendingExternalBrowser());

        var response = newBrowser().get("google/email/confirm");

        assertThat(response.statusCode()).as("status").isEqualTo(401);
    }

    @Test
    void confirmationFromAnotherBrowserAnswers204WithoutCreatingAccountNorSession()
            throws Exception {
        String link = requestedEmailLink(pendingExternalBrowser());
        var other = newBrowser();

        var response = other.post("google/email/confirm", Map.of("token", link));

        assertThat(response.statusCode()).as("status").isEqualTo(204);
        assertThat(count("auth_accounts")).as("accounts").isZero();
        assertThat(other.get("session").statusCode()).as("session status").isEqualTo(401);
    }

    @Test
    void secondConfirmationOfTheSameLinkAnswers410() throws Exception {
        String link = requestedEmailLink(pendingExternalBrowser());
        var other = newBrowser();
        assertThat(other.post("google/email/confirm", Map.of("token", link)).statusCode())
                .as("given: first confirmation")
                .isEqualTo(204);

        var response = other.post("google/email/confirm", Map.of("token", link));

        assertThat(response.statusCode()).as("status").isEqualTo(410);
    }

    @Test
    void callbackAfterEmailConfirmationAsksForUsername() throws Exception {
        var other = externalEmailConfirmedElsewhere();
        var flow = other.start();

        var response = other.callback(flow, false);

        assertThat(response.statusCode()).as("status").isEqualTo(302);
        assertThat(other.stage()).as("stage").isEqualTo("CHOOSE_USERNAME");
    }

    @Test
    void signupAfterEmailConfirmationAnswers200() throws Exception {
        var other = externalEmailConfirmedElsewhere();
        pending(other);

        var response = other.post("google/signup", Map.of("username", "External"));

        assertThat(response.statusCode()).as("status").isEqualTo(200);
    }
}
