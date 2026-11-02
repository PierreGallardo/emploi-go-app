package fr.emploigo.auth.api.google;

import static org.assertj.core.api.Assertions.assertThat;

import fr.emploigo.support.FakeGoogle;
import fr.emploigo.support.FakeProviders;
import fr.emploigo.support.MailCounters;

import org.junit.jupiter.api.Test;

import java.util.Map;

class GoogleOutageIT extends GoogleAuthenticationSupport {
    @Test
    void providerUnavailableAtTokenExchangeReportsUnavailableAndCreatesNoAccount()
            throws Exception {
        FakeGoogle.mode = "ERROR";
        var browser = newBrowser();
        var flow = browser.start();

        var response = browser.callback(flow, false);

        assertThat(response.statusCode()).as("status").isEqualTo(302);
        assertThat(browser.errorCode()).as("error code").isEqualTo("UNAVAILABLE");
        assertThat(count("auth_accounts")).as("accounts").isZero();
    }

    @Test
    void linkDuringAnOutageOfTheEmailAssociatesTheSubjectAndSendsNoMail() throws Exception {
        long id = account("Local", FakeGoogle.email, true);
        var browser = accessVerifiedBrowser();
        var mails = new MailCounters();

        var response = linkWhileEmailProviderIsDown(browser);

        assertThat(response.statusCode()).as("status").isEqualTo(200);
        assertThat(flag(response, "changed")).as("changed").isTrue();
        assertThat(linkedAccount()).as("account linked to the subject").isEqualTo(id);
        assertThat(mails.calls()).as("provider calls").isEqualTo(1);
        assertThat(credentialVersion(id)).as("credential version").isZero();
        assertThat(browser.get("session").statusCode()).as("session status").isEqualTo(200);
        assertThat(browser.username()).as("session username").isEqualTo("local");
    }

    @Test
    void secondLinkAfterAnOutageOfTheEmailAnswers400InvalidFlowAndLinksNothingMore()
            throws Exception {
        account("Local", FakeGoogle.email, true);
        var browser = accessVerifiedBrowser();
        linkedWhileProviderDown(browser);

        var response = confirmLink(browser);

        assertThat(response.statusCode()).as("status").isEqualTo(400);
        assertThat(code(response)).as("error code").isEqualTo("INVALID_FLOW");
        assertThat(count("auth_google_identities")).as("linked identities").isEqualTo(1);
    }

    @Test
    void emailRequestWhileProviderDownAnswers503AndKeepsThePendingVerification() throws Exception {
        var browser = pendingExternalBrowser();
        FakeProviders.mailStatus = 503;

        var response = browser.post("google/email", Map.of());

        assertThat(response.statusCode()).as("status").isEqualTo(503);
        assertThat(pendingVerifications()).as("pending verifications").isEqualTo(1);
    }
}
