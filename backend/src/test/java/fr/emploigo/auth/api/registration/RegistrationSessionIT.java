package fr.emploigo.auth.api.registration;

import static fr.emploigo.support.HostCookies.SESSION_COOKIE;

import static org.assertj.core.api.Assertions.assertThat;

import fr.emploigo.auth.api.GoogleBrowser;
import fr.emploigo.support.Browser;
import fr.emploigo.support.FakeGoogle;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

/**
 * The registration a browser asked for lives in its session, until it is confirmed, replaced,
 * forgotten at a login or a logout, or older than its lifetime: a browser without it is refused
 * without any email.
 */
class RegistrationSessionIT extends RegistrationTestBase {
    private void assertForgotten(Browser browser) throws Exception {
        resetQuota("registration-mail");
        var resend = resend(browser);
        assertThat(resend.statusCode()).as("status of the resend").isEqualTo(410);
        assertThat(code(resend)).as("error code").isEqualTo("NO_PENDING_REGISTRATION");
    }

    @Test
    void refusedSignupKeepsNoRegistrationAndCreatesNoSession() throws Exception {
        var browser = newBrowser();

        var refused = signup(browser, DEMO_EMAIL, "abcdef1!");

        assertThat(refused.statusCode()).as("status of the signup").isEqualTo(400);
        assertThat(browser.cookies).as("cookies").doesNotContainKey(SESSION_COOKIE);
        assertForgotten(browser);
    }

    @Test
    void confirmationForgetsTheRegistrationOfTheBrowser() throws Exception {
        var browser = newBrowser();
        String code = pendingSignup(browser, DEMO_EMAIL);
        assertThat(confirm(browser, code).statusCode()).as("given: confirmed").isEqualTo(200);

        assertForgotten(browser);
    }

    @Test
    void loginOfAnAccountForgetsTheRegistrationOfTheBrowser() throws Exception {
        var owner = newBrowser();
        String code = pendingSignup(owner, "owner@example.invalid");
        assertThat(confirm(owner, code).statusCode()).as("given: account").isEqualTo(200);
        var browser = newBrowser();
        pendingSignup(browser, DEMO_EMAIL);

        var login = login(browser, "owner@example.invalid");

        assertThat(login.statusCode()).as("status of the login").isEqualTo(200);
        assertForgotten(browser);
    }

    @Test
    void googleSignupForgetsTheRegistrationOfTheBrowser() throws Exception {
        var browser = new GoogleBrowser(port, json);
        pendingSignup(browser, DEMO_EMAIL);
        FakeGoogle.email = "google_owner@gmail.com";
        assertThat(browser.callback(browser.start(), false).statusCode())
                .as("given: Google callback accepted")
                .isEqualTo(302);

        var google = browser.post("google/signup", Map.of("username", "google_owner"));

        assertThat(google.statusCode()).as("status of the Google signup").isEqualTo(200);
        assertForgotten(browser);
    }

    @Test
    void logoutForgetsTheRegistrationOfTheBrowser() throws Exception {
        var browser = newBrowser();
        pendingSignup(browser, DEMO_EMAIL);

        var logout = browser.post("logout", Map.of());

        assertThat(logout.statusCode()).as("status of the logout").isEqualTo(204);
        assertForgotten(browser);
    }

    @Test
    void registrationIsForgottenTwentyFourHoursAfterItsLastCode() throws Exception {
        var browser = newBrowser();
        pendingSignup(browser, DEMO_EMAIL);
        clock.now = clock.now.plus(Duration.ofHours(24));

        var confirmation = confirm(browser, pendingCode(DEMO_EMAIL));

        assertThat(confirmation.statusCode()).as("status").isEqualTo(410);
        assertThat(code(confirmation)).as("error code").isEqualTo("NO_PENDING_REGISTRATION");
    }

    @Test
    void resendKeepsTheRegistrationTwentyFourHoursAfterTheNewCode() throws Exception {
        var browser = newBrowser();
        pendingSignup(browser, DEMO_EMAIL);
        clock.now = clock.now.plus(Duration.ofHours(23));
        String code = resentCode(browser, DEMO_EMAIL);
        clock.now = clock.now.plus(Duration.ofMinutes(10));

        var confirmation = confirm(browser, code);

        assertThat(confirmation.statusCode()).isEqualTo(200);
    }

    @Test
    void signupForAnotherEmailInTheSameBrowserReplacesItsRegistration() throws Exception {
        var browser = newBrowser();
        String first = pendingSignup(browser, "first@example.invalid");
        String second = pendingSignup(browser, DEMO_EMAIL);

        var firstCode = confirm(browser, first);
        var secondCode = confirm(browser, second);

        assertThat(firstCode.statusCode()).as("status of the code of the first").isEqualTo(400);
        assertThat(secondCode.statusCode()).as("status of the code of the second").isEqualTo(200);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT email_key FROM auth_accounts", String.class))
                .as("email of the account")
                .isEqualTo(DEMO_EMAIL);
    }

    /**
     * Someone else in another tab of the same browser, within the delay between two emails: the
     * refused signup gives that tab no registration, so it never confirms the earlier one.
     */
    @Test
    void signupRefusedDuringTheCooldownGivesTheTabNoRegistration() throws Exception {
        var earlier = newBrowser();
        String code = pendingSignup(earlier, DEMO_EMAIL);
        var later = earlier.copy();
        later.registration = null;
        var again = signup(later, DEMO_EMAIL);
        assertThat(code(again)).as("given: error code").isEqualTo("MAIL_RECENTLY_SENT");

        var confirmation = confirm(later, code);

        assertThat(again.headers().firstValue("Registration-Id"))
                .as("identifier given to the tab")
                .isEmpty();
        assertThat(confirmation.statusCode()).as("status").isEqualTo(410);
        assertThat(count("auth_accounts")).as("accounts").isZero();
    }

    /**
     * Someone else in another tab of the same browser, once the delay between two emails is over:
     * the tab of the earlier signup never confirms the credentials of the later one.
     */
    @Test
    void tabOfAnEarlierSignupCannotConfirmTheLaterSignupOfTheSameBrowser() throws Exception {
        var earlier = newBrowser();
        pendingSignup(earlier, DEMO_EMAIL);
        var later = earlier.copy();
        permitNextMail(DEMO_EMAIL);
        String code = pendingSignup(later, DEMO_EMAIL);

        var confirmation = confirm(earlier, code);

        assertThat(confirmation.statusCode()).as("status").isEqualTo(410);
        assertThat(code(confirmation)).as("error code").isEqualTo("NO_PENDING_REGISTRATION");
        assertThat(count("auth_accounts")).as("accounts").isZero();
    }
}
