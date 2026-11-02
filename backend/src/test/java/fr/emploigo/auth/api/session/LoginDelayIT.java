package fr.emploigo.auth.api.session;

import static fr.emploigo.support.HostCookies.SESSION_COOKIE;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;

import fr.emploigo.auth.infrastructure.quota.TestQuotaKeys;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.stream.IntStream;

class LoginDelayIT extends AuthenticationSessionSupport {
    @Test
    void failuresBeforeTheLimitThenTheNextAttemptAnswers429WithRetryAfter()
            throws Exception {
        assertThat(wrongPasswords(EMAIL, 5)).as("given: five failures").containsOnly(401);

        var response = wrongPassword(EMAIL);

        assertThat(response.statusCode()).as("status").isEqualTo(429);
        assertThat(response.headers().firstValue("Retry-After").map(Integer::parseInt))
                .as("Retry-After, in seconds")
                .hasValueSatisfying(wait -> assertThat(wait).isBetween(1, 5));
        assertThat(code(response)).as("code").isEqualTo("RATE_LIMITED");
        assertThat(response.body())
                .as("body")
                .doesNotContain("15 minutes")
                .doesNotContain("secondes");
    }

    @Test
    void correctPasswordDuringTheDelayAnswers429CreatesNoSessionAndCountsNowhere()
            throws Exception {
        assertThat(wrongPasswords(EMAIL, 5)).as("given: five failures").containsOnly(401);
        var failures = redis.opsForValue().get(failuresKey(EMAIL));
        var address = redis.opsForValue().get(addressKey());
        var browser = newBrowser();

        var response = login(browser, EMAIL, browser.csrf());

        assertThat(response.statusCode()).as("status").isEqualTo(429);
        assertThat(browser.cookies).as("cookies").doesNotContainKey(SESSION_COOKIE);
        assertThat(redis.opsForValue().get(failuresKey(EMAIL))).as("failures").isEqualTo(failures);
        assertThat(redis.opsForValue().get(addressKey()))
                .as("attempts of the address")
                .isEqualTo(address);
    }

    @Test
    void failureAfterTheDelayEndedIsFollowedByALongerDelay() throws Exception {
        assertThat(wrongPasswords(EMAIL, 5)).as("given: five failures").containsOnly(401);
        redis.delete(delayKey(EMAIL));
        assertThat(wrongPassword(EMAIL).statusCode())
                .as("given: the sixth failure is judged")
                .isEqualTo(401);

        var response = wrongPassword(EMAIL);

        assertThat(response.statusCode()).as("status").isEqualTo(429);
        assertThat(response.headers().firstValue("Retry-After").map(Integer::parseInt))
                .as("Retry-After, in seconds")
                .hasValueSatisfying(wait -> assertThat(wait).isBetween(6, 10));
    }

    @Test
    void successfulLoginAfterTheDelayForgetsTheFailuresButNotTheAttemptsOfTheAddress()
            throws Exception {
        assertThat(wrongPasswords(EMAIL, 5)).as("given: five failures").containsOnly(401);
        redis.delete(delayKey(EMAIL));
        var browser = newBrowser();
        assertThat(login(browser, EMAIL, browser.csrf()).statusCode())
                .as("given: login accepted once the delay ended")
                .isEqualTo(200);

        assertThat(redis.hasKey(failuresKey(EMAIL))).as("failures kept").isFalse();
        assertThat(redis.hasKey(delayKey(EMAIL))).as("delay kept").isFalse();
        assertThat(redis.opsForValue().get(addressKey()))
                .as("attempts of the address")
                .isEqualTo("6");
        assertThat(wrongPasswords(EMAIL, 5)).as("a new budget of failures").containsOnly(401);
    }

    @Test
    void emailAndUsernameOfTheSameAccountDoNotShareACounterSoAnAnswerNeverLinksThem()
            throws Exception {
        assertThat(wrongPasswords(EMAIL, 5))
                .as("given: the email is at its limit")
                .containsOnly(401);
        assertThat(wrongPassword(EMAIL).statusCode())
                .as("given: the email is delayed")
                .isEqualTo(429);

        var byUsername = wrongPassword("Ecole_demo");

        assertThat(byUsername.statusCode()).as("status of the username").isEqualTo(401);
    }

    @Test
    void parallelWrongPasswordsNeverGoBeyondTheFreeFailuresBeforeTheDelay() throws Exception {
        // An attempt admitted by the quota keeps its failure even when the limit of simultaneous
        // computations answers it with a 503: the admitted attempts are counted whatever their end.
        doReturn(false).when(passwords).matches(any(), any());
        // The first calls of a cold path are slow enough for computations to overlap: warm it up on
        // other identities.
        IntStream.range(0, 3)
                .boxed()
                .forEach(unchecked(i -> wrongPassword("echauffement-" + i).statusCode())::apply);
        clearInvocations(passwords);
        var statuses = inParallel(20, i -> wrongPassword(EMAIL).statusCode());

        assertThat(statuses.stream().filter(status -> status == 401 || status == 503).count())
                .as("attempts admitted")
                .isEqualTo(5);
        assertThat(statuses.stream().filter(status -> status == 429).count())
                .as("refused by the delay")
                .isEqualTo(15);
    }

    @Test
    void counterOfTheEarlierVersionIsIgnoredAndDoesNotRefuse() throws Exception {
        redis.opsForValue()
                .set(
                        "auth:limits:identity:"
                                + TestQuotaKeys.KEYS.identity("identifier:legacy@example.invalid"),
                        "10",
                        Duration.ofMinutes(15));

        var response = wrongPassword("legacy@example.invalid");

        assertThat(response.statusCode()).as("status").isEqualTo(401);
    }

    @Test
    void countersLostAtRedisAreNotAQuarantineSoTheLoginAnswersAgain() throws Exception {
        assertThat(wrongPasswords(EMAIL, 5)).as("given: five failures").containsOnly(401);
        assertThat(wrongPassword(EMAIL).statusCode()).as("given: limit reached").isEqualTo(429);
        redis.delete(redis.keys("auth:limits:*"));

        var response = wrongPassword(EMAIL);

        assertThat(response.statusCode()).as("status").isEqualTo(401);
    }
}
