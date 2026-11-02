package fr.emploigo.auth.api.session;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Duration;

class SessionLifetimeIT extends AuthenticationSessionSupport {
    @Test
    void sessionInactiveForLessThanTheTimeoutOfTheConfigurationAnswers200() throws Exception {
        var browser = loggedIn();
        inactiveFor(sessionTimeout.minusSeconds(30));

        var response = browser.get("session");

        assertThat(response.statusCode()).as("status").isEqualTo(200);
    }

    @Test
    void sessionInactiveForLongerThanTheTimeoutOfTheConfigurationAnswers401() throws Exception {
        var browser = loggedIn();
        expireTheSessionOfInactivity();

        var response = browser.get("session");

        assertThat(response.statusCode()).as("status").isEqualTo(401);
        assertThat(code(response)).as("code").isEqualTo("UNAUTHENTICATED");
    }

    @Test
    void sessionOlderThanItsAbsoluteLifetimeAnswers401() throws Exception {
        var browser = loggedIn();
        clock.now = clock.now.plus(Duration.ofHours(8));

        var response = browser.get("session");

        assertThat(response.statusCode()).as("status").isEqualTo(401);
        assertThat(code(response)).as("code").isEqualTo("UNAUTHENTICATED");
    }

    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("accountChangesThatRefuseTheReconnection")
    void sessionOfAnAccountThatLostItsAccessAnswers401Unauthenticated(String change)
            throws Exception {
        var browser = loggedIn();
        jdbc.update(
                """
                UPDATE auth_accounts SET %s WHERE id = ?
                """
                        .formatted(change),
                accountId);

        var response = browser.get("session");

        assertThat(response.statusCode()).as("status").isEqualTo(401);
        assertThat(code(response)).as("code").isEqualTo("UNAUTHENTICATED");
    }

    @Test
    void familyResumedEvery29DaysForMoreThanAYearStaysValid() throws Exception {
        var browser = loggedIn();
        var start = clock.instant();

        var statuses = resumesEvery29Days(browser, 14);

        assertThat(Duration.between(start, clock.instant()))
                .as("time since the login")
                .isGreaterThan(Duration.ofDays(365));
        assertThat(statuses).as("statuses of the resumes").hasSize(14).containsOnly(200);
    }

    @Test
    void familyUnusedForMoreThanThirtyDaysAfterAYearOfResumesAnswers401() throws Exception {
        var browser = loggedIn();
        assertThat(resumesEvery29Days(browser, 14))
                .as("given: statuses of the resumes")
                .containsOnly(200);
        clock.now = clock.now.plus(Duration.ofDays(30)).plusSeconds(1);

        var response = resume(browser);

        assertThat(response.statusCode()).as("status").isEqualTo(401);
    }
}
