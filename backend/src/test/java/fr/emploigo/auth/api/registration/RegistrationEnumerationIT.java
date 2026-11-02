package fr.emploigo.auth.api.registration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import fr.emploigo.auth.api.GoogleBrowser;
import fr.emploigo.auth.domain.TestPasswords;
import fr.emploigo.support.Browser;
import fr.emploigo.support.FakeGoogle;
import fr.emploigo.support.MailCounters;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * What a browser can learn about an email from its answers: a free email with a pending
 * registration and an email with an account (which keeps no registration) must be answered alike.
 * The free email is answered by the database; the email of the account has no registration, so its
 * pages are answered by Redis. A browser only asks about the email its session signed up for: one
 * that signed up for none is refused without any email, so an email never seen tells nothing. The
 * password of a signup opens nothing at the sign-in, whatever the email.
 */
class RegistrationEnumerationIT extends RegistrationTestBase {
    private static final String FREE_EMAIL = "free@example.invalid";
    private static final String NEVER_EMAIL = "never@example.invalid";
    private static final String GOOGLE_EMAIL = "taken-by-google@gmail.com";

    /** The browsers of a signup for a free email and for the email of an account. */
    private record Pages(Browser free, Browser ghost) {}

    private Pages signedUpOnBothEmails() throws Exception {
        existingAccount();
        var pages = new Pages(newBrowser(), newBrowser());
        assertThat(signup(pages.free(), FREE_EMAIL).statusCode())
                .as("given: signup with a free email")
                .isEqualTo(202);
        assertThat(signup(pages.ghost(), KNOWN_EMAIL).statusCode())
                .as("given: signup with the email of an account")
                .isEqualTo(202);
        return pages;
    }

    @Test
    void sixteenConfirmationsAreAnsweredAlikeForBothEmails() throws Exception {
        var pages = signedUpOnBothEmails();

        var free = answers(attempts(pages.free(), wrongCode(pendingCode(FREE_EMAIL)), 16));
        var ghost = answers(attempts(pages.ghost(), "000000", 16));

        assertThat(free).as("answers for the free email").hasSize(16);
        assertThat(ghost).as("answers for the email of the account").isEqualTo(free);
    }

    @Test
    void browserWithoutSignupIsRefusedWithoutAnyEmail() throws Exception {
        var browser = newBrowser();

        var confirmation = confirm(browser, "000000");
        var resend = resend(browser);

        assertThat(List.of(confirmation, resend))
                .as("answers without a signup")
                .allSatisfy(
                        response -> {
                            assertThat(response.statusCode()).as("status").isEqualTo(410);
                            assertThat(code(response))
                                    .as("error code")
                                    .isEqualTo("NO_PENDING_REGISTRATION");
                        });
    }

    /**
     * The answers of a page that fails 15 times over three codes: the second request is another
     * page, which takes the email over with its failures; then the hour of block starts. {@code
     * wrongCode} gives a code that the email does not expect.
     */
    private List<String> fifteenFailuresOverThreeCodes(
            String email, String username, Supplier<String> wrongCode) throws Exception {
        var first = newBrowser();
        var second = newBrowser();
        var log = new ArrayList<HttpResponse<String>>();
        assertThat(signup(first, email).statusCode())
                .as("given: first signup")
                .isEqualTo(202);
        log.addAll(attempts(first, wrongCode.get(), 5));
        permitNextMail(email);
        log.add(signup(second, email));
        log.addAll(attempts(second, wrongCode.get(), 5));
        permitNextMail(email);
        log.add(resend(second));
        log.addAll(attempts(second, wrongCode.get(), 5));
        var mismatched = new HashMap<>(signupBody(email));
        mismatched.put("confirmation", TestPasswords.random());
        log.add(second.post("signup", mismatched));
        log.add(confirm(second, "123456"));
        resetQuota("registration-mail");
        log.add(signup(newBrowser(), email));
        return answers(log);
    }

    @Test
    void failuresInheritedOverThreeCodesAndTheHourOfBlockAreAnsweredAlikeForBothEmails()
            throws Exception {
        existingAccount();

        var free =
                fifteenFailuresOverThreeCodes(
                        FREE_EMAIL, "enum-free", () -> wrongCode(pendingCode(FREE_EMAIL)));
        var ghost = fifteenFailuresOverThreeCodes(KNOWN_EMAIL, "enum-ghost", () -> "000000");

        assertThat(free).as("answers for the free email").hasSize(20);
        assertThat(free.get(18)).as("answer of the hour of block").startsWith("429 60 ");
        assertThat(free.getLast()).as("signup of another page").startsWith("202");
        assertThat(ghost).as("answers for the email of the account").isEqualTo(free);
    }

    /**
     * The answers of the first page, after four wrong codes, once a second page asked for a code:
     * by a new signup ({@code takeOver}) or by a resend. Either replaces the code of the first
     * page. The second page signed up for the email before the first, so that it may ask for a
     * resend.
     */
    private List<String> fifthCodeAfterAnotherPageAsked(
            String email, String username, Supplier<String> wrongCode, boolean takeOver)
            throws Exception {
        var second = replacedPage(email);
        var first = newBrowser();
        assertThat(signup(first, email).statusCode())
                .as("given: first signup")
                .isEqualTo(202);
        var log = new ArrayList<>(attempts(first, wrongCode.get(), 4));
        permitNextMail(email);
        log.add(takeOver ? signup(second, email) : resend(second));
        log.addAll(attempts(first, wrongCode.get(), 2));
        clock.now = clock.now.plus(Duration.ofMinutes(16));
        log.addAll(attempts(first, wrongCode.get(), 1));
        return answers(log);
    }

    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("requestsOfAnotherPage")
    void firstPageIsAnsweredAlikeForBothEmailsOnceAnotherPageAskedForACode(boolean takeOver)
            throws Exception {
        existingAccount();

        var free =
                fifthCodeAfterAnotherPageAsked(
                        FREE_EMAIL,
                        "enum-free",
                        () -> wrongCode(pendingCode(FREE_EMAIL)),
                        takeOver);
        var ghost =
                fifthCodeAfterAnotherPageAsked(KNOWN_EMAIL, "enum-ghost", () -> "000000", takeOver);

        assertThat(free).as("answers for the free email").hasSize(8);
        assertThat(ghost).as("answers for the email of the account").isEqualTo(free);
    }

    static Stream<Arguments> requestsOfAnotherPage() {
        return Stream.of(
                argumentSet("nouvelle inscription d'une autre page", true),
                argumentSet("renvoi demandé par une autre page", false));
    }

    private void accountCreatedByGoogle(String email) throws Exception {
        FakeGoogle.email = email;
        var browser = new GoogleBrowser(port, json);
        var flow = browser.start();
        assertThat(browser.callback(flow, false).statusCode())
                .as("given: Google callback accepted")
                .isEqualTo(302);
        assertThat(browser.post("google/signup", Map.of("username", "google_owner")).statusCode())
                .as("given: account created by Google")
                .isEqualTo(200);
    }

    /** A new signup of the page, then two wrong codes: the code of the email is not expected. */
    private List<String> newSignupThenTwoCodes(
            Browser page, String username, String email, Supplier<String> wrongCode)
            throws Exception {
        permitNextMail(email);
        var log = new ArrayList<>(List.of(signup(page, email)));
        log.addAll(attempts(page, wrongCode.get(), 2));
        return answers(log);
    }

    /**
     * Google creates the account of an email while its registration is pending: 16 minutes later,
     * the page of that registration asks for a new code and is answered like the page of a free
     * email, not with the expired code of the registration.
     */
    @Test
    void pageOfARegistrationClosedByAGoogleAccountIsAnsweredLikeThePageOfAFreeEmail()
            throws Exception {
        var closed = newBrowser();
        var free = newBrowser();
        assertThat(signup(closed, GOOGLE_EMAIL).statusCode())
                .as("given: signup with the email that Google takes")
                .isEqualTo(202);
        assertThat(signup(free, FREE_EMAIL).statusCode())
                .as("given: signup with a free email")
                .isEqualTo(202);
        accountCreatedByGoogle(GOOGLE_EMAIL);
        clock.now = clock.now.plus(Duration.ofMinutes(16));

        var freeAnswers =
                newSignupThenTwoCodes(
                        free, "enum-free", FREE_EMAIL, () -> wrongCode(pendingCode(FREE_EMAIL)));
        var closedAnswers =
                newSignupThenTwoCodes(closed, "enum-google", GOOGLE_EMAIL, () -> "000000");

        assertThat(freeAnswers).as("answers for the free email").hasSize(3);
        assertThat(freeAnswers.getLast()).as("wrong code").startsWith("400 - ");
        assertThat(closedAnswers).as("answers for the email Google took").isEqualTo(freeAnswers);
        assertThat(pendingRegistrations()).as("pending registrations").isEqualTo(1);
    }

    private List<String> twoResends(Browser page, String email) throws Exception {
        permitNextMail(email);
        var one = resend(page);
        var two = resend(page);
        return List.of(
                one.statusCode() + " " + one.body(),
                two.statusCode() + " " + code(two) + " " + two.body());
    }

    @Test
    void twoResendsWithinTheCooldownAreAnsweredAlikeForBothEmails() throws Exception {
        var pages = signedUpOnBothEmails();

        var free = twoResends(pages.free(), FREE_EMAIL);
        var ghost = twoResends(pages.ghost(), KNOWN_EMAIL);

        assertThat(free).as("answers for the free email").hasSize(2);
        assertThat(free.getLast()).as("second resend").startsWith("429 MAIL_RECENTLY_SENT");
        assertThat(ghost).as("answers for the email of the account").isEqualTo(free);
    }

    @Test
    void signInWithThePasswordOfTheSignupAnswers401AlikeForTheThreeEmails() throws Exception {
        signedUpOnBothEmails();
        // The account has a usable hash of another password, like any real account.
        jdbc.update(
                "UPDATE auth_accounts SET password_hash = ? WHERE email_key = ?",
                hashing.encode("Une-Autre-Phrase-2026-fictive!"),
                KNOWN_EMAIL);
        var mail = new MailCounters();

        var free = login(newBrowser(), FREE_EMAIL, password);
        var ghost = login(newBrowser(), KNOWN_EMAIL, password);
        var never = login(newBrowser(), NEVER_EMAIL, password);

        assertThat(free.statusCode()).as("status for the free email").isEqualTo(401);
        assertThat(code(free)).as("error code").isEqualTo("INVALID_CREDENTIALS");
        assertThat(ghost.body()).as("answer for the email of the account").isEqualTo(free.body());
        assertThat(never.body()).as("answer for the email never seen").isEqualTo(free.body());
        assertThat(mail.calls()).as("provider calls").isZero();
    }
}
