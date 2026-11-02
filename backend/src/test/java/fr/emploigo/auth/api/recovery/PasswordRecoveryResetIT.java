package fr.emploigo.auth.api.recovery;

import static fr.emploigo.support.FakeProviders.mailStatus;
import static fr.emploigo.support.HostCookies.CSRF_COOKIE;
import static fr.emploigo.support.HostCookies.RECONNECTION_COOKIE;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;

import fr.emploigo.auth.domain.TestPasswords;
import fr.emploigo.support.Browser;
import fr.emploigo.support.MailCounters;
import fr.emploigo.support.SqlFailure;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

class PasswordRecoveryResetIT extends PasswordRecoveryIntegrationTest {
    @Test
    void getOnPasswordResetIsRefusedAndKeepsPassword() throws Exception {
        long id = owner();
        requestedLink(EMAIL);

        var response = newBrowser().get("password/reset");

        assertThat(response.statusCode()).as("status").isEqualTo(401);
        assertThat(passwordMatches(id, original)).as("password is still the original one").isTrue();
    }

    @Test
    void resetWithValidLinkChangesThePassword() throws Exception {
        long id = owner();
        String link = requestedLink(EMAIL);

        var response = resetPassword(newBrowser(), link);

        assertThat(response.statusCode()).as("status").isEqualTo(200);
        assertThat(credentialVersion(id)).as("credential version").isEqualTo(1);
        assertThat(passwordMatches(id, replacement)).as("password is the replacement").isTrue();
    }

    @Test
    void resetAnswersOnlyThatThePasswordChanged() throws Exception {
        owner();
        String link = requestedLink(EMAIL);

        var response = resetPassword(newBrowser(), link);

        assertThat(flag(response, "changed")).as("password changed").isTrue();
        assertThat(json.readTree(response.body()).has("notificationSent"))
                .as("notificationSent in the body")
                .isFalse();
    }

    @Test
    void resetSendsTheChangeNoticeToTheOwnerOnce() throws Exception {
        owner();
        String link = requestedLink(EMAIL);
        var mail = new MailCounters();

        resetPassword(newBrowser(), link);

        assertThat(mail.sends()).as("messages sent").isEqualTo(1);
    }

    @Test
    void resetLeavesTheRecoveringBrowserWithoutSession() throws Exception {
        owner();
        String link = requestedLink(EMAIL);
        var recovery = newBrowser();

        var response = resetPassword(recovery, link);

        assertThat(response.statusCode()).as("status").isEqualTo(200);
        assertThat(recovery.cookies).as("cookies").doesNotContainKey(RECONNECTION_COOKIE);
        assertThat(recovery.get("session").statusCode()).as("session status").isEqualTo(401);
    }

    @Test
    void firstWriteAfterTheResetSucceedsWithANewCsrfToken() throws Exception {
        owner();
        String link = requestedLink(EMAIL);
        var recovery = newBrowser();
        resetPassword(recovery, link);
        assertThat(recovery.cookies).as("cookies").doesNotContainKey(CSRF_COOKIE);

        var response = login(recovery, replacement);

        assertThat(response.statusCode()).as("status").isEqualTo(200);
        assertThat(recovery.cookies).as("cookies").containsKey(CSRF_COOKIE);
    }

    static Stream<Arguments> previousBrowsers() {
        return Stream.of(
                argumentSet("première session ouverte", 0),
                argumentSet("seconde session ouverte", 1));
    }

    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("previousBrowsers")
    void resetRejectsThePreviousSessionOfEveryBrowser(int index) throws Exception {
        long id = owner();
        var browsers = List.of(loggedIn(), loggedIn());
        assertThat(browsers.get(index).get("session").statusCode())
                .as("given: session open")
                .isEqualTo(200);
        resetDone(requestedLink(EMAIL));
        assertThat(sessionRows(id)).as("given: session rows kept by the reset").isEqualTo(2);

        var response = browsers.get(index).get("session");

        assertThat(response.statusCode()).as("status").isEqualTo(401);
    }

    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("previousBrowsers")
    void resetRejectsTheReconnectionCookieOfEveryBrowser(int index) throws Exception {
        long id = owner();
        var browsers = List.of(loggedIn(), loggedIn());
        resetDone(requestedLink(EMAIL));
        assertThat(activeReconnections(id))
                .as("given: reconnection families kept by the reset")
                .isEqualTo(2);

        var response = browsers.get(index).post("resume", Map.of());

        assertThat(response.statusCode()).as("status").isEqualTo(401);
    }

    @Test
    void loginWithTheOldPasswordIsRefusedAfterReset() throws Exception {
        owner();
        resetDone(requestedLink(EMAIL));

        var response = login(newBrowser(), original);

        assertThat(response.statusCode()).as("status").isEqualTo(401);
    }

    @Test
    void loginWithTheNewPasswordIsAcceptedAfterReset() throws Exception {
        owner();
        resetDone(requestedLink(EMAIL));

        var response = login(newBrowser(), replacement);

        assertThat(response.statusCode()).as("status").isEqualTo(200);
    }

    enum InvalidLink {
        MALFORMED("lien malformé"),
        WRONG_PURPOSE("jeton d'une autre finalité");

        private final String label;

        InvalidLink(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** A recovery link whose purpose or format is wrong, derived from a genuine link. */
    private String invalidLink(String link, InvalidLink kind) {
        return switch (kind) {
            case MALFORMED -> "malformed";
            case WRONG_PURPOSE ->
                    link.substring(0, 37)
                            + tokens.googleToken(UUID.fromString(link.substring(0, 36)));
        };
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(InvalidLink.class)
    void resetWithMalformedOrWrongPurposeLinkAnswers400AndKeepsPassword(InvalidLink kind)
            throws Exception {
        long id = owner();
        String invalid = invalidLink(requestedLink(EMAIL), kind);

        var response = resetPassword(newBrowser(), invalid);

        assertThat(response.statusCode()).as("status").isEqualTo(400);
        assertThat(credentialVersion(id)).as("credential version").isZero();
        assertThat(passwordMatches(id, original)).as("password is still the original one").isTrue();
    }

    @Test
    void resetWithLinkReplacedByANewRequestAnswersReplacedLink() throws Exception {
        long id = owner();
        String old = requestedLink(EMAIL);
        permitNextMail(EMAIL);
        requestedLink(EMAIL);

        var response = resetPassword(newBrowser(), old);

        assertThat(response.statusCode()).as("status").isEqualTo(410);
        assertThat(code(response)).as("code").isEqualTo("REPLACED_LINK");
        assertThat(credentialVersion(id)).as("credential version").isZero();
    }

    @Test
    void resetWithExpiredLinkAnswersExpiredLinkAndKeepsPassword() throws Exception {
        long id = owner();
        String link = requestedLink(EMAIL);
        clock.now = clock.now.plus(Duration.ofMinutes(31));
        clearInvocations(passwords);

        var response = resetPassword(newBrowser(), link);

        assertThat(response.statusCode()).as("status").isEqualTo(410);
        assertThat(code(response)).as("code").isEqualTo("EXPIRED_LINK");
        verifyNoInteractions(passwords);
        assertThat(credentialVersion(id)).as("credential version").isZero();
        assertThat(passwordMatches(id, original)).as("password is still the original one").isTrue();
    }

    @Test
    void resetWithAlreadyUsedLinkAnswersUsedLinkAndKeepsThePasswordOfTheFirstChange()
            throws Exception {
        long id = owner();
        String link = requestedLink(EMAIL);
        resetDone(link);

        var response = newBrowser().post("password/reset", change(link, TestPasswords.random()));

        assertThat(response.statusCode()).as("status").isEqualTo(410);
        assertThat(code(response)).as("code").isEqualTo("USED_LINK");
        assertThat(credentialVersion(id)).as("credential version").isEqualTo(1);
        assertThat(passwordMatches(id, replacement)).as("password is the replacement").isTrue();
    }

    @Test
    void resetWithAlreadyUsedLinkSendsNoEmail() throws Exception {
        owner();
        String link = requestedLink(EMAIL);
        resetDone(link);
        var mail = new MailCounters();

        resetPassword(newBrowser(), link);

        assertThat(mail.calls()).as("provider calls").isZero();
    }

    static Stream<Arguments> unacceptablePasswords() {
        return Stream.of(
                argumentSet("trop court", "Ab1!xyz", "Ab1!xyz", "INVALID_INPUT"),
                argumentSet("sans majuscule", "abcdef1!", "abcdef1!", "WEAK_PASSWORD"),
                argumentSet(
                        "confirmation différente",
                        "Une phrase fictive 1",
                        "Une autre phrase 1",
                        "INVALID_INPUT"));
    }

    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("unacceptablePasswords")
    void resetWithAnUnacceptablePasswordAnswers400AndKeepsPasswordSessionAndLink(
            String password, String confirmation, String expectedCode) throws Exception {
        long id = owner();
        var browser = loggedIn();
        String link = requestedLink(EMAIL);

        var response =
                browser.post(
                                "password/reset",
                                Map.of(
                                        "token",
                                        link,
                                        "password",
                                        password,
                                        "confirmation",
                                        confirmation));

        assertThat(response.statusCode()).as("status").isEqualTo(400);
        assertThat(code(response)).as("code").isEqualTo(expectedCode);
        assertThat(credentialVersion(id)).as("credential version").isZero();
        assertThat(passwordMatches(id, original)).as("password is still the original one").isTrue();
        assertThat(link(EMAIL)).as("pending link").isEqualTo(link);
        assertThat(browser.get("session").statusCode()).as("session status").isEqualTo(200);
        assertThat(activeReconnections(id)).as("active reconnection families").isEqualTo(1);
    }

    @Test
    void resetAtTheLimitIsRefusedAndKeepsTheLinkUsable() throws Exception {
        long id = owner();
        String link = requestedLink(EMAIL);

        try (var gate = hashingGate()) {
            gate.saturate(hashing.limit());

            var refused = resetPassword(newBrowser(), link);

            assertThat(refused.statusCode()).as("status").isEqualTo(503);
            assertThat(refused.headers().firstValue("Retry-After")).as("Retry-After").contains("5");
            assertThat(code(refused)).as("code").isEqualTo("BUSY");
            assertThat(credentialVersion(id)).as("credential version").isZero();
            assertThat(link(EMAIL)).as("pending link").isEqualTo(link);

            gate.releaseAndWait();
            var accepted = resetPassword(newBrowser(), link);

            assertThat(accepted.statusCode()).as("status once free").isEqualTo(200);
            assertThat(credentialVersion(id)).as("credential version once free").isEqualTo(1);
        }
    }

    @Test
    void resetWithEmailProviderDownStillChangesThePassword() throws Exception {
        long id = owner();
        String link = requestedLink(EMAIL);
        mailStatus = 503;

        var response = resetPassword(newBrowser(), link);

        assertThat(response.statusCode()).as("status").isEqualTo(200);
        assertThat(flag(response, "changed")).as("password changed").isTrue();
        assertThat(credentialVersion(id)).as("credential version").isEqualTo(1);
    }

    private HttpResponse<String> resetWithFailedPasswordChange(Browser browser, String link)
            throws Exception {
        try (var _ = SqlFailure.inject(jdbc, "auth_accounts", "BEFORE UPDATE")) {
            return resetPassword(browser, link);
        }
    }

    @Test
    void resetWhenThePasswordChangeFailsAnswers503AndRollsBackThePasswordChange() throws Exception {
        long id = owner();
        var browser = loggedIn();
        String link = requestedLink(EMAIL);

        var response = resetWithFailedPasswordChange(browser, link);

        assertThat(response.statusCode()).as("status").isEqualTo(503);
        assertThat(credentialVersion(id)).as("credential version").isZero();
        assertThat(passwordMatches(id, original)).as("password is still the original one").isTrue();
        assertThat(link(EMAIL)).as("pending link").isEqualTo(link);
        assertThat(browser.get("session").statusCode()).as("session status").isEqualTo(200);
    }

    @Test
    void resetRetriedAfterPasswordChangeFailureChangesPassword() throws Exception {
        long id = owner();
        var browser = loggedIn();
        String link = requestedLink(EMAIL);
        assertThat(resetWithFailedPasswordChange(browser, link).statusCode())
                .as("given: reset refused")
                .isEqualTo(503);

        var retry = resetPassword(browser, link);

        assertThat(retry.statusCode()).as("status").isEqualTo(200);
        assertThat(credentialVersion(id)).as("credential version").isEqualTo(1);
    }
}
