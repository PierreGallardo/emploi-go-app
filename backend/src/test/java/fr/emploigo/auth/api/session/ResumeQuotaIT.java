package fr.emploigo.auth.api.session;

import static fr.emploigo.support.HostCookies.RECONNECTION_COOKIE;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.stream.IntStream;

class ResumeQuotaIT extends AuthenticationSessionSupport {
    @Test
    void resumesOfElevenAccountsFromTheSameAddressAreAllAccepted() throws Exception {
        // Every request of the tests reaches the API from 127.0.0.1.
        var browsers = IntStream.rangeClosed(1, 11).mapToObj(this::browserOfANewAccount).toList();

        var statuses =
                browsers.stream().map(unchecked(browser -> resume(browser).statusCode())).toList();

        assertThat(statuses).as("statuses of the resumes").hasSize(11).containsOnly(200);
    }

    @Test
    void resumesDoNotConsumeTheCountersOfTheLogin() throws Exception {
        var browser = loggedIn();
        var before = quotaCounters();
        assertThat(before).as("given: counters of the login").isNotEmpty();

        var statuses = browser.postStatuses("resume", "", 8);

        assertThat(statuses).as("statuses of the resumes").hasSize(8).containsOnly(200);
        // The resumes have a counter of their own, by family; every other counter is untouched.
        assertThat(quotaCounters())
                .as("counters of the quota")
                .containsAllEntriesOf(before)
                .hasSize(before.size() + 1)
                .containsEntry(resumeCounterKey(accountId), "8");
    }

    @Test
    void resumesWithAWrongSecretFromTheSameAddressAreAllRefusedWith401() throws Exception {
        var browser = loggedIn();
        UUID family =
                jdbc.queryForObject(
                        "SELECT id FROM auth_reconnections WHERE account_id = ?",
                        UUID.class,
                        accountId);
        browser.cookies.put(RECONNECTION_COOKIE, cookieWithAWrongSecret(family));

        var statuses = browser.postStatuses("resume", "", 15);

        assertThat(statuses).as("statuses of the resumes").hasSize(15).containsOnly(401);
    }
}
