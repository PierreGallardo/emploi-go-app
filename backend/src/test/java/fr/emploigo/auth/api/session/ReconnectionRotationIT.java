package fr.emploigo.auth.api.session;

import static fr.emploigo.support.HostCookies.RECONNECTABLE_COOKIE;
import static fr.emploigo.support.HostCookies.RECONNECTION_COOKIE;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;

import fr.emploigo.auth.application.session.ResumeSessionUseCase;
import fr.emploigo.support.LogCapture;

import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

class ReconnectionRotationIT extends AuthenticationSessionSupport {
    @Test
    void resumeReplayingARotatedReconnectionCookieWithinTheToleranceServesANewSecret()
            throws Exception {
        var browser = loggedIn();
        String first = browser.cookies.get(RECONNECTION_COOKIE);
        assertThat(resume(browser).statusCode()).as("given: first resume accepted").isEqualTo(200);
        String second = browser.cookies.get(RECONNECTION_COOKIE);
        clock.now = clock.now.plus(TOLERANCE);
        browser.cookies.put(RECONNECTION_COOKIE, first);

        var response = resume(browser);

        assertThat(response.statusCode()).as("status").isEqualTo(200);
        assertThat(browser.cookies.get(RECONNECTION_COOKIE))
                .as("reconnection cookie")
                .isNotIn(first, second);
        assertThat(browser.cookies).as("cookies kept").containsKey(RECONNECTABLE_COOKIE);
        assertThat(familyRevoked()).as("family revoked").isFalse();
        assertThat(browser.get("session").statusCode()).as("status of the session").isEqualTo(200);
    }

    @Test
    void resumeReplayingARotatedCookieAfterTheToleranceClearsTheIndicator()
            throws Exception {
        var browser = loggedIn();
        String first = browser.cookies.get(RECONNECTION_COOKIE);
        assertThat(resume(browser).statusCode()).as("given: first resume accepted").isEqualTo(200);
        clock.now = clock.now.plus(TOLERANCE).plusSeconds(1);
        browser.cookies.put(RECONNECTION_COOKIE, first);

        var response = resume(browser);

        assertThat(response.statusCode()).as("status").isEqualTo(401);
        assertThat(code(response)).as("code").isEqualTo("RECONNECTION_REFUSED");
        assertThat(browser.cookies).as("cookies kept").doesNotContainKey(RECONNECTABLE_COOKIE);
    }

    @Test
    void twoConcurrentResumesWithTheSameCookieAreBothServedWithoutRevokingTheFamily()
            throws Exception {
        String cookie = loggedIn().cookies.get(RECONNECTION_COOKIE);
        var first = holding(cookie);
        var second = holding(cookie);
        String firstCsrf = first.csrf();
        String secondCsrf = second.csrf();

        var responses =
                overlappingAtTheRow(
                        "auth_accounts",
                        accountId,
                        () -> first.post("resume", "", firstCsrf),
                        () -> second.post("resume", "", secondCsrf));

        assertThat(responses)
                .as("statuses")
                .extracting(HttpResponse::statusCode)
                .containsExactly(200, 200);
        assertThat(familyRevoked()).as("family revoked").isFalse();
        assertThat(resume(holding(first.cookies.get(RECONNECTION_COOKIE))).statusCode())
                .as("status of the resume with the cookie of the first answer")
                .isEqualTo(200);
    }

    @Test
    void secretOfALostResponseIsRefusedWithoutRevocationOnceItsReplacementIsServed()
            throws Exception {
        var rotated = rotatedOnce();
        assertThat(resume(holding(rotated.previous())).statusCode())
                .as("given: replay served")
                .isEqualTo(200);

        var response = resume(holding(rotated.previous()));

        assertThat(response.statusCode()).as("status").isEqualTo(401);
        assertThat(familyRevoked()).as("family revoked").isFalse();
    }

    @Test
    void holderReplayingAfterTheToleranceOfAReplayServedToAThiefRevokesTheFamilyOfBoth()
            throws Exception {
        var rotated = rotatedOnce();
        var thief = holding(rotated.previous());
        assertThat(resume(thief).statusCode()).as("given: replay served").isEqualTo(200);
        clock.now = clock.now.plus(TOLERANCE).plusSeconds(1);

        var holder = resume(rotated.owner());

        assertThat(holder.statusCode()).as("status of the holder").isEqualTo(401);
        assertThat(familyRevoked()).as("family revoked").isTrue();
        assertThat(resume(thief).statusCode()).as("status of the thief").isEqualTo(401);
    }

    @Test
    void secretOfTheLostResponseStillResumesWhenItsReplacementIsServed() throws Exception {
        var rotated = rotatedOnce();
        assertThat(resume(holding(rotated.previous())).statusCode())
                .as("given: replay served")
                .isEqualTo(200);

        var response = resume(rotated.owner());

        assertThat(response.statusCode()).as("status").isEqualTo(200);
    }

    @Test
    void sessionInProgressIsRefusedAfterAPreviousSecretReplayedAfterTheTolerance()
            throws Exception {
        var rotated = rotatedOnce();
        assertThat(rotated.owner().get("session").statusCode())
                .as("given: session accepted")
                .isEqualTo(200);
        clock.now = clock.now.plus(TOLERANCE).plusSeconds(1);
        resume(holding(rotated.previous()));

        var response = rotated.owner().get("session");

        assertThat(response.statusCode()).as("status").isEqualTo(401);
    }

    @Test
    void currentSecretIsRefusedAfterAPreviousSecretReplayedAfterTheTolerance() throws Exception {
        var rotated = rotatedOnce();
        clock.now = clock.now.plus(TOLERANCE).plusSeconds(1);
        resume(holding(rotated.previous()));

        var response = resume(rotated.owner());

        assertThat(response.statusCode()).as("status").isEqualTo(401);
    }

    @Test
    void secretOlderThanThePreviousAnswers401WithoutRevokingTheFamily() throws Exception {
        var rotated = rotatedOnce();
        assertThat(resume(rotated.owner()).statusCode())
                .as("given: second resume accepted")
                .isEqualTo(200);
        clock.now = clock.now.plus(TOLERANCE).plusSeconds(1);

        var response = resume(holding(rotated.previous()));

        assertThat(response.statusCode()).as("status").isEqualTo(401);
        assertThat(familyRevoked()).as("family revoked").isFalse();
    }

    @Test
    void previousSecretReplayedWithinTheToleranceIsServedAndLogsNothing() throws Exception {
        var rotated = rotatedOnce();

        try (var logs = LogCapture.of(ResumeSessionUseCase.class)) {
            resume(holding(rotated.previous()));

            assertThat(logs.events()).as("events").isEmpty();
        }
    }

    @Test
    void previousSecretReplayedAfterTheToleranceLogsOneWarningWithoutSecretNorFamily()
            throws Exception {
        var rotated = rotatedOnce();
        UUID family =
                jdbc.queryForObject(
                        "SELECT id FROM auth_reconnections WHERE account_id = ?",
                        UUID.class,
                        accountId);
        clock.now = clock.now.plus(TOLERANCE).plusSeconds(1);

        try (var logs = LogCapture.of(ResumeSessionUseCase.class)) {
            resume(holding(rotated.previous()));
            resume(holding(rotated.previous()));

            assertThat(logs.events())
                    .as("events")
                    .singleElement()
                    .satisfies(
                            event -> {
                                assertThat(event.getLevel()).as("level").isEqualTo(Level.WARN);
                                assertThat(event.getFormattedMessage())
                                        .as("message")
                                        .isNotBlank()
                                        .doesNotContain(rotated.previous())
                                        .doesNotContain(rotated.previous().substring(37))
                                        .doesNotContain(family.toString())
                                        .doesNotContain(String.valueOf(accountId));
                                assertThat(event.getArgumentArray())
                                        .as("arguments")
                                        .isNullOrEmpty();
                            });
        }
    }

    @Test
    void previousSecretAfterTheExpiryAnswers401WithoutRevokingNorLogging()
            throws Exception {
        var rotated = rotatedOnce();
        clock.now = clock.now.plus(Duration.ofDays(30)).plusSeconds(1);

        try (var logs = LogCapture.of(ResumeSessionUseCase.class)) {
            var response = resume(holding(rotated.previous()));

            assertThat(response.statusCode()).as("status").isEqualTo(401);
            assertThat(familyRevoked()).as("family revoked").isFalse();
            assertThat(logs.events()).as("events").isEmpty();
        }
    }
}
