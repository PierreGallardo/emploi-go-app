package fr.emploigo.auth.api.registration;

import static org.assertj.core.api.Assertions.assertThat;

import fr.emploigo.auth.domain.TestPasswords;
import fr.emploigo.support.MailCounters;

import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.List;
import java.util.stream.IntStream;

class RegistrationBlockIT extends RegistrationTestBase {
    private java.time.Instant blockedUntil(String email) {
        return jdbc.queryForObject(
                        "SELECT codes_blocked_until FROM auth_registrations WHERE email_key = ?",
                        Timestamp.class,
                        email)
                .toInstant();
    }

    @Test
    void fifteenthFailureBlocksTheCodesForAnHourAnswering429() throws Exception {
        var page = newBrowser();
        pendingSignup(page, BLOCKED_EMAIL);

        var blocking = failThreeCodes(page, BLOCKED_EMAIL);

        assertThat(blocking.statusCode()).as("status").isEqualTo(429);
        assertThat(code(blocking)).as("error code").isEqualTo("CODES_BLOCKED");
        assertThat(retryAfter(blocking)).as("Retry-After").isEqualTo(3600L);
        assertThat(codeFailures(BLOCKED_EMAIL)).as("failures of the registration").isEqualTo(15);
        assertThat(blockedUntil(BLOCKED_EMAIL))
                .as("end of the block")
                .isEqualTo(clock.instant().plus(Duration.ofHours(1)));
    }

    @Test
    void resendAndConfirmAreRefusedWith429DuringTheHourOfBlock() throws Exception {
        var page = blockedRegistration();
        String code = pendingCode(BLOCKED_EMAIL);
        long codes = count("auth_verifications");
        var mail = new MailCounters();
        resetQuota("registration-mail");
        var resend = resend(page);
        var confirmation = confirm(page, code);

        assertThat(List.of(resend, confirmation))
                .as("answers")
                .allSatisfy(
                        response -> {
                            assertThat(response.statusCode()).as("status").isEqualTo(429);
                            assertThat(code(response)).as("error code").isEqualTo("CODES_BLOCKED");
                            assertThat(retryAfter(response)).as("Retry-After").isEqualTo(3600L);
                        });
        assertThat(mail.calls()).as("provider calls").isZero();
        assertThat(count("auth_verifications")).as("codes").isEqualTo(codes);
        assertThat(count("auth_accounts")).as("accounts").isZero();
    }

    /**
     * A new signup draws a new key, even in the blocked browser: it is answered like another page,
     * and the hour of block of the registration stays.
     */
    @Test
    void signupAgainInTheBlockedBrowserAnswers202WithOnlyANoticeAndKeepsTheBlock()
            throws Exception {
        var page = blockedRegistration();
        String code = pendingCode(BLOCKED_EMAIL);
        var before = snapshot();
        resetQuota("registration-mail");
        var mail = new MailCounters();

        var signup = signup(page, BLOCKED_EMAIL);
        var confirmation = confirm(page, code);

        assertThat(signup.statusCode()).as("status of the signup").isEqualTo(202);
        assertThat(mail.sends()).as("messages sent").isEqualTo(1);
        assertThat(snapshot()).as("rows").isEqualTo(before);
        assertThat(confirmation.statusCode()).as("status of the right code").isEqualTo(400);
        assertThat(count("auth_accounts")).as("accounts").isZero();
    }

    @Test
    void signupFromAnotherPageDuringTheBlockAnswers202WithOnlyANoticeAndReplacesNothing()
            throws Exception {
        blockedRegistration();
        var before = snapshot();
        resetQuota("registration-mail");
        var mail = new MailCounters();

        var signup = signup(newBrowser(), BLOCKED_EMAIL, TestPasswords.random());

        assertThat(signup.statusCode()).as("status").isEqualTo(202);
        assertThat(signup.body()).as("body").isEmpty();
        assertThat(snapshot()).as("rows").isEqualTo(before);
        assertThat(mail.sends()).as("messages sent").isEqualTo(1);
    }

    @Test
    void resendFromAnotherPageDuringTheBlockAnswers202WithOnlyANoticeAndCreatesNoCode()
            throws Exception {
        var other = replacedPage(BLOCKED_EMAIL);
        blockedRegistration();
        var before = snapshot();
        resetQuota("registration-mail");
        var mail = new MailCounters();

        var resend = resend(other);

        assertThat(resend.statusCode()).as("status").isEqualTo(202);
        assertThat(resend.body()).as("body").isEmpty();
        assertThat(snapshot()).as("rows").isEqualTo(before);
        assertThat(mail.sends()).as("messages sent").isEqualTo(1);
    }

    @Test
    void newCodeIsPossibleOnceTheHourIsOverAndTheFailuresAreKept() throws Exception {
        var page = blockedRegistration();
        clock.now = clock.now.plus(Duration.ofHours(1)).plusSeconds(1);

        String fresh = resentCode(page, BLOCKED_EMAIL);
        var confirmation = confirm(page, fresh);

        assertThat(confirmation.statusCode()).as("status").isEqualTo(200);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT code_failures FROM auth_registrations", Integer.class))
                .as("failures of the registration")
                .isEqualTo(15);
    }

    @Test
    void thirtiethFailureBlocksTheCodesAgain() throws Exception {
        var page = blockedRegistration();
        clock.now = clock.now.plus(Duration.ofHours(1)).plusSeconds(1);
        resentCode(page, BLOCKED_EMAIL);

        var blocking = failThreeCodes(page, BLOCKED_EMAIL);

        assertThat(blocking.statusCode()).as("status").isEqualTo(429);
        assertThat(code(blocking)).as("error code").isEqualTo("CODES_BLOCKED");
        assertThat(codeFailures(BLOCKED_EMAIL)).as("failures of the registration").isEqualTo(30);
        assertThat(blockedUntil(BLOCKED_EMAIL))
                .as("end of the block")
                .isEqualTo(clock.instant().plus(Duration.ofHours(1)));
    }

    @Test
    void confirmationAttemptsOfOnePageBeyondTheQuotaAnswer429AfterTheSequenceOfRefusals()
            throws Exception {
        var page = replacedPage("quota@example.invalid");
        pendingSignup(newBrowser(), "quota@example.invalid");

        var statuses = page.postStatuses("signup/confirm", confirmBody(page, "000000"), 31);

        assertThat(statuses)
                .as("statuses: four wrong codes, then too many attempts, then the quota")
                .isEqualTo(
                        IntStream.rangeClosed(1, 31)
                                .mapToObj(attempt -> attempt <= 4 ? 400 : attempt <= 30 ? 410 : 429)
                                .toList());
    }

    @Test
    void threeSimultaneousWrongConfirmationsCountThreeFailuresOnTheCodeAndTheRegistration()
            throws Exception {
        var page = newBrowser();
        pendingSignup(page, DEMO_EMAIL);

        var responses = parallelWrongConfirmationsHeldByTheRow(page, DEMO_EMAIL, 3);

        assertThat(responses.stream().map(HttpResponse::statusCode))
                .as("statuses")
                .containsExactly(400, 400, 400);
        assertThat(attemptsOnCurrentCode(DEMO_EMAIL)).as("attempts on the code").isEqualTo(3);
        assertThat(codeFailures(DEMO_EMAIL)).as("failures of the registration").isEqualTo(3);
    }

    @Test
    void twentySimultaneousWrongConfirmationsCountNoMoreThanFiveAttemptsOnTheCode()
            throws Exception {
        var page = newBrowser();
        String code = pendingSignup(page, DEMO_EMAIL);

        // 20 requests would hold the whole pool while they wait for a lock: no row lock here.
        var responses = inParallel(20, index -> confirm(page.copy(), wrongCode(code)));

        var statuses = responses.stream().map(HttpResponse::statusCode).toList();
        assertThat(statuses).as("statuses").containsOnly(400, 410);
        assertThat(statuses.stream().filter(status -> status == 400).count())
                .as("wrong codes counted")
                .isEqualTo(4);
        assertThat(attemptsOnCurrentCode(DEMO_EMAIL)).as("attempts on the code").isEqualTo(5);
        assertThat(codeFailures(DEMO_EMAIL)).as("failures of the registration").isEqualTo(5);
    }

    @Test
    void fifteenthFailureRacingWithTheFourteenthAnswersOne429AndBlocksOnce() throws Exception {
        var page = newBrowser();
        pendingSignup(page, DEMO_EMAIL);
        jdbc.update("UPDATE auth_registrations SET code_failures = 13");

        var responses = parallelWrongConfirmationsHeldByTheRow(page, DEMO_EMAIL, 2);

        assertThat(responses.stream().map(HttpResponse::statusCode))
                .as("statuses")
                .containsExactlyInAnyOrder(400, 429);
        assertThat(codeFailures(DEMO_EMAIL)).as("failures of the registration").isEqualTo(15);
        assertThat(blockedUntil(DEMO_EMAIL))
                .as("end of the block")
                .isEqualTo(clock.instant().plus(Duration.ofHours(1)));
    }
}
