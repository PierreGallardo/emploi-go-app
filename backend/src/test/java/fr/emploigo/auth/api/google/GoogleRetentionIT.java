package fr.emploigo.auth.api.google;

import static fr.emploigo.support.HostCookies.SESSION_COOKIE;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import java.util.Map;

class GoogleRetentionIT extends GoogleAuthenticationSupport {
    @Test
    void newFlowAfter25HoursLeavesTheFlowsOfIssuedSessionsToTheWorker() throws Exception {
        signedUpWithGmail();
        assertThat(issuedFlows()).as("given: a flow issued a session").isEqualTo(1);

        newFlowAfter25Hours();

        assertThat(issuedFlows()).as("flows that issued a session").isEqualTo(1);
    }

    @Test
    void thePurgeOfTheWorkerAfter25HoursDeletesTheFlowsOfIssuedSessions() throws Exception {
        signedUpWithGmail();
        assertThat(issuedFlows()).as("given: a flow issued a session").isEqualTo(1);

        purgeAfter25Hours();

        assertThat(issuedFlows()).as("flows that issued a session").isZero();
    }

    @Test
    void resumeAfter25HoursStillRestoresTheGoogleSession() throws Exception {
        var browser = signedUpWithGmail().browser();
        assertThat(issuedFlows()).as("given: a flow issued a session").isEqualTo(1);
        purgeAfter25Hours();
        assertThat(issuedFlows()).as("given: the flow was purged").isZero();
        browser.cookies.remove(SESSION_COOKIE);

        var response = browser.post("resume", Map.of());

        assertThat(response.statusCode()).as("status").isEqualTo(200);
    }
}
