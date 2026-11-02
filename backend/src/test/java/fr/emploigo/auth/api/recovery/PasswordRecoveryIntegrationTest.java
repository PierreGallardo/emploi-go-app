package fr.emploigo.auth.api.recovery;

import static fr.emploigo.support.HostCookies.RECONNECTION_COOKIE;

import static org.assertj.core.api.Assertions.assertThat;

import fr.emploigo.auth.application.AuthenticationIntegrationTest;
import fr.emploigo.auth.application.confirmation.ConfirmationTokens;
import fr.emploigo.auth.domain.TestPasswords;
import fr.emploigo.support.Browser;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;

import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;

abstract class PasswordRecoveryIntegrationTest extends AuthenticationIntegrationTest {
    static final String EMAIL = "fixture@example.invalid";
    static final String ABSENT_EMAIL = "absent@example.invalid";

    @Autowired ConfirmationTokens tokens;
    String original;
    String replacement;

    @BeforeEach
    void fixture() {
        original = UUID.randomUUID() + " phrase initiale 🔑 ";
        replacement = TestPasswords.random();
    }

    long account(String address, boolean verified, boolean localPassword) {
        return jdbc.queryForObject(
                """
                INSERT INTO auth_accounts(
                    username, email_key, password_hash, email_verified, enabled)
                VALUES (?, ?, ?, ?, TRUE) RETURNING id
                """,
                Long.class,
                address,
                address,
                localPassword ? passwords.encode(original) : null,
                verified);
    }

    long owner() {
        return account(EMAIL, true, true);
    }

    String link(String address) {
        UUID id =
                jdbc.queryForObject(
                        """
                        SELECT r.id FROM auth_password_resets r
                        JOIN auth_accounts a ON a.id = r.account_id
                        WHERE a.email_key = ? AND r.status = 'PENDING'
                        """,
                        UUID.class,
                        address);
        return id + "." + tokens.recoveryToken(id);
    }

    void permitNextMail(String address) {
        permitNextMail("recovery-mail", address);
    }

    Map<String, Object> change(String link, String password) {
        return Map.of("token", link, "password", password, "confirmation", password);
    }

    HttpResponse<String> forgot(Browser browser, String address) throws Exception {
        return browser.post("password/forgot", Map.of("email", address));
    }

    HttpResponse<String> resetPassword(Browser browser, String link) throws Exception {
        return browser.post("password/reset", change(link, replacement));
    }

    HttpResponse<String> login(Browser browser, String password) throws Exception {
        return login(browser, EMAIL, password);
    }

    HttpResponse<String> login(Browser browser, String identifier, String password)
            throws Exception {
        return browser.post(
                "login",
                Map.of("identifier", identifier, "password", password, "rememberMe", true));
    }

    long credentialVersion(long accountId) {
        return jdbc.queryForObject(
                "SELECT credential_version FROM auth_accounts WHERE id = ?", Long.class, accountId);
    }

    boolean passwordMatches(long accountId, String password) {
        return passwords.matches(
                password,
                jdbc.queryForObject(
                        "SELECT password_hash FROM auth_accounts WHERE id = ?",
                        String.class,
                        accountId));
    }

    long activeReconnections(long accountId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM auth_reconnections WHERE account_id = ? AND NOT revoked",
                Long.class,
                accountId);
    }

    long sessionRows(long accountId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM spring_session WHERE principal_name = ?",
                Long.class,
                Long.toString(accountId));
    }

    Browser loggedIn() throws Exception {
        var browser = newBrowser();
        assertThat(login(browser, original).statusCode())
                .as("given: login accepted")
                .isEqualTo(200);
        assertThat(browser.cookies)
                .as("given: reconnection cookie issued")
                .containsKey(RECONNECTION_COOKIE);
        return browser;
    }

    String requestedLink(String address) throws Exception {
        assertThat(forgot(newBrowser(), address).statusCode())
                .as("given: recovery requested")
                .isEqualTo(202);
        return link(address);
    }

    void resetDone(String link) throws Exception {
        assertThat(resetPassword(newBrowser(), link).statusCode())
                .as("given: reset accepted")
                .isEqualTo(200);
    }
}
