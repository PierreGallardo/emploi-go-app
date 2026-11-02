package fr.emploigo.auth.api.registration;

import static fr.emploigo.support.FakeProviders.MAIL_PAYLOADS;

import static org.assertj.core.api.Assertions.assertThat;

import fr.emploigo.auth.domain.TestPasswords;
import fr.emploigo.support.Containers;
import fr.emploigo.support.MailCounters;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.List;

class RegistrationPageBindingIT extends RegistrationTestBase {
    private Timestamp blockedUntilOrNull(String email) {
        return jdbc.queryForObject(
                "SELECT codes_blocked_until FROM auth_registrations WHERE email_key = ?",
                Timestamp.class,
                email);
    }

    @Test
    void codeOfTheThirdPartyRequestDoesNotOpenTheAccountOfTheHolderInAnotherBrowser()
            throws Exception {
        var thirdParty = newBrowser();
        var holder = newBrowser();
        String thirdPartyPassword = TestPasswords.random();
        assertThat(
                        signup(thirdParty, SHARED_EMAIL, thirdPartyPassword)
                                .statusCode())
                .as("given: signup of the third party")
                .isEqualTo(202);
        permitNextMail(SHARED_EMAIL);
        String holderCode = pendingSignup(holder, SHARED_EMAIL);
        var before = snapshot();

        var inThirdPartyBrowser = confirm(thirdParty, holderCode);

        assertThat(inThirdPartyBrowser.statusCode())
                .as("status in the browser of the third party")
                .isEqualTo(400);
        assertThat(code(inThirdPartyBrowser)).as("error code").isEqualTo("INVALID_CODE");
        assertThat(snapshot()).as("rows").isEqualTo(before);
        assertThat(count("auth_accounts")).as("accounts").isZero();
        assertThat(thirdParty.get("session").statusCode())
                .as("session of the third party")
                .isEqualTo(401);
    }

    @Test
    void codeOfTheHolderOpensAnAccountWithTheNameAndPasswordOfTheHolderOnly() throws Exception {
        var thirdParty = newBrowser();
        var holder = newBrowser();
        String thirdPartyPassword = TestPasswords.random();
        assertThat(
                        signup(thirdParty, SHARED_EMAIL, thirdPartyPassword)
                                .statusCode())
                .as("given: signup of the third party")
                .isEqualTo(202);
        permitNextMail(SHARED_EMAIL);
        String holderCode = pendingSignup(holder, SHARED_EMAIL);

        var confirmation = confirm(holder, holderCode);

        assertThat(confirmation.statusCode()).as("status").isEqualTo(200);
        assertThat(sessionUsername(holder)).as("username").isNull();
        assertThat(login(newBrowser(), SHARED_EMAIL, password).statusCode())
                .as("login with the password of the holder")
                .isEqualTo(200);
        assertThat(login(newBrowser(), SHARED_EMAIL, thirdPartyPassword).statusCode())
                .as("login with the password of the third party")
                .isEqualTo(401);
        assertThat(count("auth_accounts")).as("accounts").isEqualTo(1);
    }

    @Test
    void browserOfTheHolderCannotConfirmWithAnyCodeOnceAThirdPartyReplacedTheRegistration()
            throws Exception {
        var holder = newBrowser();
        var thirdParty = newBrowser();
        String holderCode = pendingSignup(holder, SHARED_EMAIL);
        permitNextMail(SHARED_EMAIL);
        assertThat(
                        signup(
                                        thirdParty,
                                        SHARED_EMAIL,
                                        TestPasswords.random())
                                .statusCode())
                .as("given: signup of the third party")
                .isEqualTo(202);
        String latest = pendingCode(SHARED_EMAIL);
        var before = snapshot();

        var oldCode = confirm(holder, holderCode);
        var latestCode = confirm(holder, latest);

        assertThat(List.of(oldCode, latestCode))
                .as("answers on the page of the holder")
                .allSatisfy(
                        response -> {
                            assertThat(response.statusCode()).as("status").isEqualTo(400);
                            assertThat(code(response)).as("error code").isEqualTo("INVALID_CODE");
                        });
        assertThat(snapshot()).as("rows").isEqualTo(before);
        assertThat(count("auth_accounts")).as("accounts").isZero();
        assertThat(holder.get("session").statusCode())
                .as("session of the holder")
                .isEqualTo(401);
    }

    @Test
    void foreignPageFollowsTheSequenceOfAttemptsInRedisAndChangesNothingInTheDatabase()
            throws Exception {
        var foreign = replacedPage(DEMO_EMAIL);
        String code = pendingSignup(newBrowser(), DEMO_EMAIL);
        var before = snapshot();

        var responses = attempts(foreign, wrongCode(code), 16);

        var statuses = responses.stream().map(HttpResponse::statusCode).toList();
        assertThat(statuses.subList(0, 4)).as("attempts 1 to 4").containsOnly(400);
        assertThat(statuses.subList(4, 16)).as("attempts 5 to 16").containsOnly(410);
        assertThat(code(responses.get(0))).as("code of the first").isEqualTo("INVALID_CODE");
        assertThat(code(responses.get(4)))
                .as("code of the fifth")
                .isEqualTo("CODE_ATTEMPTS_EXCEEDED");
        assertThat(snapshot()).as("rows").isEqualTo(before);
    }

    @Test
    void fifteenthFailureOfAForeignPageAcrossThreeCodesAnswers429AndRefusesItsNewCodes()
            throws Exception {
        var foreign = replacedPage(DEMO_EMAIL);
        String code = pendingSignup(newBrowser(), DEMO_EMAIL);
        attempts(foreign, wrongCode(code), 5);
        resentCode(foreign, DEMO_EMAIL);
        attempts(foreign, wrongCode(code), 5);
        resentCode(foreign, DEMO_EMAIL);
        var third = attempts(foreign, wrongCode(code), 5);
        var after = confirm(foreign, wrongCode(code));
        resetQuota("registration-mail");
        var resend = resend(foreign);

        assertThat(third.getLast().statusCode()).as("fifteenth failure").isEqualTo(429);
        assertThat(code(third.getLast())).as("error code").isEqualTo("CODES_BLOCKED");
        // The wait is the TTL of a Redis key, read after it was set: up to a few seconds less.
        assertThat(retryAfter(third.getLast())).as("Retry-After").isBetween(3590L, 3600L);
        assertThat(after.statusCode()).as("status afterwards").isEqualTo(429);
        assertThat(resend.statusCode()).as("resend of the blocked page").isEqualTo(429);
        assertThat(codeFailures(DEMO_EMAIL)).as("failures of the registration").isZero();
        assertThat(blockedUntilOrNull(DEMO_EMAIL)).as("block of the registration").isNull();
    }

    @Test
    void newCodeResetsTheAttemptsOfTheForeignPageThatAsksForIt() throws Exception {
        var foreign = replacedPage(DEMO_EMAIL);
        String code = pendingSignup(newBrowser(), DEMO_EMAIL);
        var exhausted = attempts(foreign, wrongCode(code), 5);
        assertThat(exhausted.getLast().statusCode()).as("given: attempts exhausted").isEqualTo(410);
        String fresh = resentCode(foreign, DEMO_EMAIL);

        var again = confirm(foreign, wrongCode(fresh));

        assertThat(again.statusCode()).as("status").isEqualTo(400);
        assertThat(code(again)).as("error code").isEqualTo("INVALID_CODE");
    }

    @Test
    void newCodeOfAnotherPageDoesNotResetTheAttemptsOfAForeignPage() throws Exception {
        var foreign = replacedPage(DEMO_EMAIL);
        var page = newBrowser();
        String code = pendingSignup(page, DEMO_EMAIL);
        var exhausted = attempts(foreign, wrongCode(code), 5);
        assertThat(exhausted.getLast().statusCode()).as("given: attempts exhausted").isEqualTo(410);
        resentCode(page, DEMO_EMAIL);

        var again = confirm(foreign, wrongCode(code));

        assertThat(again.statusCode()).as("status").isEqualTo(410);
        assertThat(code(again)).as("error code").isEqualTo("CODE_ATTEMPTS_EXCEEDED");
    }

    /**
     * A replaced page answers as a page whose own code was issued: it expires 15 minutes after its
     * code, not after the code of the page that replaced it.
     */
    @Test
    void replacedPageAnswersCodeExpiredFifteenMinutesAfterItsOwnCode() throws Exception {
        var first = newBrowser();
        String old = pendingSignup(first, DEMO_EMAIL);
        clock.now = clock.now.plus(Duration.ofMinutes(10));
        permitNextMail(DEMO_EMAIL);
        assertThat(signup(newBrowser(), DEMO_EMAIL).statusCode())
                .as("given: signup of another page ten minutes later")
                .isEqualTo(202);

        var before = confirm(first, wrongCode(old));
        clock.now = clock.now.plus(Duration.ofMinutes(6));
        var after = confirm(first, wrongCode(old));

        assertThat(before.statusCode()).as("status before 15 minutes").isEqualTo(400);
        assertThat(code(before)).as("error code before 15 minutes").isEqualTo("INVALID_CODE");
        assertThat(after.statusCode()).as("status after 15 minutes").isEqualTo(410);
        assertThat(code(after)).as("error code after 15 minutes").isEqualTo("CODE_EXPIRED");
    }

    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("loginsOfAPendingRegistration")
    void loginWithAnyPasswordOfAPendingRegistrationAnswers401WithoutEmail(
            String identifier, boolean registrationPassword) throws Exception {
        pendingSignup(newBrowser(), DEMO_EMAIL);
        permitNextMail(DEMO_EMAIL);
        var mail = new MailCounters();
        var before = snapshot();
        var second = newBrowser();

        var login =
                login(
                        second,
                        identifier,
                        registrationPassword ? password : TestPasswords.random());

        assertThat(login.statusCode()).as("status").isEqualTo(401);
        assertThat(code(login)).as("error code").isEqualTo("INVALID_CREDENTIALS");
        assertThat(mail.calls()).as("provider calls").isZero();
        assertThat(snapshot()).as("rows").isEqualTo(before);
    }

    @Test
    void signupAgainInTheSameBrowserReplacesTheCodeAndTheOldOneIsRefused() throws Exception {
        var browser = newBrowser();
        String old = pendingSignup(browser, DEMO_EMAIL);
        permitNextMail(DEMO_EMAIL);

        String fresh = pendingSignup(browser, DEMO_EMAIL);
        var replaced = confirm(browser, old);
        var confirmation = confirm(browser, fresh);

        assertThat(fresh).as("new code").isNotEqualTo(old);
        assertThat(replaced.statusCode()).as("status of the old code").isEqualTo(410);
        assertThat(code(replaced)).as("error code").isEqualTo("CODE_REPLACED");
        assertThat(confirmation.statusCode()).as("status of the new code").isEqualTo(200);
    }

    @Test
    void signupAgainFromAnotherBrowserReplacesTheCodeAndTheOldBrowserIsRefused() throws Exception {
        var first = newBrowser();
        String old = pendingSignup(first, DEMO_EMAIL);
        permitNextMail(DEMO_EMAIL);
        var second = newBrowser();

        String fresh = pendingSignup(second, DEMO_EMAIL);
        var oldBrowser = confirm(first, fresh);
        var newBrowser = confirm(second, fresh);

        assertThat(fresh).as("new code").isNotEqualTo(old);
        assertThat(oldBrowser.statusCode()).as("status in the old browser").isEqualTo(400);
        assertThat(newBrowser.statusCode()).as("status in the new browser").isEqualTo(200);
        assertThat(count("auth_accounts")).as("accounts").isEqualTo(1);
    }

    @Test
    void signupWithAnExistingAccountEmailKeepsNothingAndSendsTheNoticeOfTheAccount()
            throws Exception {
        existingAccount();
        var mail = new MailCounters();

        var signup = signup(newBrowser(), KNOWN_EMAIL);

        assertThat(signup.statusCode()).as("status").isEqualTo(202);
        assertThat(pendingRegistrations()).as("pending registrations").isZero();
        assertThat(verifications("PENDING")).as("pending codes").isZero();
        assertThat(mail.sends()).as("messages sent").isEqualTo(1);
        assertThat(String.join("\n", MAIL_PAYLOADS.values()))
                .as("email sent")
                .contains("Tu as déjà un compte Emploi Go")
                .doesNotContainPattern("(?<![0-9a-fA-F-])[0-9]{6}(?![0-9a-fA-F-])");
    }

    @Test
    void resendForTheEmailOfAnAccountSendsTheNoticeOfTheAccountAndNoCode() throws Exception {
        existingAccount();
        var page = newBrowser();
        signup(page, KNOWN_EMAIL);
        resetQuota("registration-mail");
        var mail = new MailCounters();

        var resend = resend(page);

        assertThat(resend.statusCode()).as("status").isEqualTo(202);
        assertThat(mail.sends()).as("messages sent").isEqualTo(1);
        assertThat(String.join("\n", MAIL_PAYLOADS.values()))
                .as("emails sent")
                .contains("Tu as déjà un compte Emploi Go")
                .doesNotContainPattern("(?<![0-9a-fA-F-])[0-9]{6}(?![0-9a-fA-F-])");
    }

    @Test
    void signupWithTheEmailOfAnExistingAccountIsAnsweredLikeANewEmail() throws Exception {
        existingAccount();
        var known = signup(newBrowser(), KNOWN_EMAIL);

        var unknown = signup(newBrowser(), NEW_EMAIL);

        assertThat(known.statusCode()).as("status").isEqualTo(unknown.statusCode());
        assertThat(known.body()).as("body").isEqualTo(unknown.body());
        assertThat(stableHeaders(known)).as("headers").isEqualTo(stableHeaders(unknown));
    }

    @Test
    void thirdPartyCannotUseUpTheConfirmationQuotaOfThePageOfTheHolder() throws Exception {
        var thirdParty = replacedPage(DEMO_EMAIL);
        var page = newBrowser();
        String code = pendingSignup(page, DEMO_EMAIL);

        var foreign = attempts(thirdParty, wrongCode(code), 31);
        var confirmation = confirm(page, code);

        assertThat(foreign.getLast().statusCode()).as("status for the foreign page").isEqualTo(429);
        assertThat(confirmation.statusCode()).as("status for the holder").isEqualTo(200);
    }

    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("mailboxes")
    void signupWithTakenUsernameAnswers202(String email) throws Exception {
        existingAccount();
        var page = newBrowser();
        assertThat(signup(page, email).statusCode())
                .as("given: signup accepted")
                .isEqualTo(202);
        permitNextMail(email);

        var taken = signup(page, email);

        assertThat(taken.statusCode()).isEqualTo(202);
    }

    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("mailboxes")
    void signupWithAnotherEmailInTheSameBrowserAnswers202(String email) throws Exception {
        existingAccount();
        var page = newBrowser();
        assertThat(signup(page, email).statusCode())
                .as("given: signup accepted")
                .isEqualTo(202);

        var other = signup(page, "next-" + email);

        assertThat(other.statusCode()).isEqualTo(202);
    }

    @Test
    void pageThatSignedUpGetsTheSameExpiredAnswerForAFreeEmailAndForAnAccountEmail()
            throws Exception {
        existingAccount();
        var freePage = newBrowser();
        var accountPage = newBrowser();
        assertThat(signup(freePage, NEW_EMAIL).statusCode())
                .as("given: signup with a free email")
                .isEqualTo(202);
        assertThat(signup(accountPage, KNOWN_EMAIL).statusCode())
                .as("given: signup with the email of an account")
                .isEqualTo(202);
        clock.now = clock.now.plus(Duration.ofMinutes(16));

        var free = confirm(freePage, "123456");
        var account = confirm(accountPage, "123456");

        assertThat(free.statusCode()).as("status for the free email").isEqualTo(410);
        assertThat(code(free)).as("error code for the free email").isEqualTo("CODE_EXPIRED");
        assertThat(account.statusCode()).as("status for the account").isEqualTo(free.statusCode());
        assertThat(stableHeaders(account)).as("headers").isEqualTo(stableHeaders(free));
        assertThat(account.body()).as("body").isEqualTo(free.body());
    }

    @Test
    void holderPageAndForeignPageGetTheSameFirstFiveAnswers() throws Exception {
        var foreign = replacedPage(DEMO_EMAIL);
        var page = newBrowser();
        String code = pendingSignup(page, DEMO_EMAIL);

        var holder = answers(attempts(page, wrongCode(code), 5));
        var others = answers(attempts(foreign, code, 5));

        assertThat(holder).as("answers of the holder").hasSize(5);
        assertThat(others)
                .as("answers of the foreign page, even with the right code")
                .isEqualTo(holder);
    }

    @Test
    void foreignPageConfirmationWhileRedisIsPausedAnswers400InvalidCodeAndNeverAnError()
            throws Exception {
        var foreign = replacedPage(DEMO_EMAIL);
        String code = pendingSignup(newBrowser(), DEMO_EMAIL);

        HttpResponse<String> refused;
        try (var _ = Containers.pauseRedis()) {
            refused = confirm(foreign, code);
        }

        assertThat(refused.statusCode()).as("status").isEqualTo(400);
        assertThat(code(refused)).as("error code").isEqualTo("INVALID_CODE");
        assertThat(count("auth_accounts")).as("accounts").isZero();
    }
}
