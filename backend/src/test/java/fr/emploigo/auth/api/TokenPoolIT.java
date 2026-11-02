package fr.emploigo.auth.api;

import static org.assertj.core.api.Assertions.assertThat;

import fr.emploigo.auth.application.AuthenticationIntegrationTest;
import fr.emploigo.auth.domain.AuthenticationSecrets;
import fr.emploigo.auth.domain.TestPasswords;
import fr.emploigo.support.PoolHold;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

/**
 * A malformed link is refused before the database, even with the pool held: anyone can send one.
 * Cookies are covered by {@link ReconnectionPoolIT}.
 */
class TokenPoolIT extends AuthenticationIntegrationTest {
    // One malformed value per route: the format rules are tested in AuthenticationTokenTest and
    // RegistrationCodeTest.
    private static final String MALFORMED_LINK = "a".repeat(80);

    @Autowired DataSource dataSource;

    @Test
    void signupConfirmationWithoutARegistrationInTheBrowserIsRefusedWithoutTakingAConnection()
            throws Exception {
        var browser = newBrowser();
        String csrf = browser.csrf();

        try (var _ = PoolHold.ofAll(dataSource)) {
            var response = browser.post("signup/confirm", Map.of("code", "12345"), csrf);

            assertThat(response.statusCode()).as("status").isEqualTo(410);
            assertThat(code(response)).as("code").isEqualTo("NO_PENDING_REGISTRATION");
        }
    }

    @Test
    void passwordResetRefusesAMalformedLinkWithoutTakingAConnection() throws Exception {
        var browser = newBrowser();
        String csrf = browser.csrf();
        String password = TestPasswords.random();
        var body = Map.of("token", MALFORMED_LINK, "password", password, "confirmation", password);

        try (var _ = PoolHold.ofAll(dataSource)) {
            var response = browser.post("password/reset", body, csrf);

            assertThat(response.statusCode()).as("status").isEqualTo(400);
            assertThat(code(response)).as("code").isEqualTo("INVALID_LINK");
        }
    }

    @Test
    void googleEmailConfirmationRefusesAMalformedLinkWithoutTakingAConnection() throws Exception {
        var browser = newBrowser();
        String csrf = browser.csrf();

        try (var _ = PoolHold.ofAll(dataSource)) {
            var response =
                    browser.post("google/email/confirm", Map.of("token", MALFORMED_LINK), csrf);

            assertThat(response.statusCode()).as("status").isEqualTo(400);
            assertThat(code(response)).as("code").isEqualTo("INVALID_LINK");
        }
    }

    // Well-formed but unknown links need the database, so the held pool refuses them: the routes
    // above do not answer early.

    private static String unknownLink() {
        return UUID.randomUUID() + "." + AuthenticationSecrets.generate();
    }

    @Test
    void signupConfirmationOfAWellFormedCodeAnswers503WhileThePoolIsHeld() throws Exception {
        var browser = newBrowser();
        String password = TestPasswords.random();
        assertThat(
                        browser.post(
                                        "signup",
                                        Map.of(
                                                "username",
                                                "pool-demo",
                                                "email",
                                                "jeune@example.invalid",
                                                "password",
                                                password,
                                                "confirmation",
                                                password,
                                                "acceptedTerms",
                                                true,
                                                "rememberMe",
                                                false))
                                .statusCode())
                .as("given: signup accepted")
                .isEqualTo(202);
        String csrf = browser.csrf();

        try (var _ = PoolHold.ofAll(dataSource)) {
            assertThat(
                            browser.post(
                                            "signup/confirm",
                                            Map.of(
                                                    "code",
                                                    "123456",
                                                    "registration",
                                                    browser.registration),
                                            csrf)
                                    .statusCode())
                    .as("status")
                    .isEqualTo(503);
        }
    }

    @Test
    void passwordResetOfAWellFormedLinkAnswers503WhileThePoolIsHeld() throws Exception {
        var browser = newBrowser();
        String csrf = browser.csrf();
        String password = TestPasswords.random();
        var body = Map.of("token", unknownLink(), "password", password, "confirmation", password);

        try (var _ = PoolHold.ofAll(dataSource)) {
            assertThat(browser.post("password/reset", body, csrf).statusCode())
                    .as("status")
                    .isEqualTo(503);
        }
    }

    @Test
    void googleEmailConfirmationOfAWellFormedLinkAnswers503WhileThePoolIsHeld() throws Exception {
        var browser = newBrowser();
        String csrf = browser.csrf();

        try (var _ = PoolHold.ofAll(dataSource)) {
            assertThat(
                            browser.post(
                                            "google/email/confirm",
                                            Map.of("token", unknownLink()),
                                            csrf)
                                    .statusCode())
                    .as("status")
                    .isEqualTo(503);
        }
    }
}
