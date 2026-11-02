package fr.emploigo.auth.api;

import static org.assertj.core.api.Assertions.assertThat;

import fr.emploigo.auth.application.AuthenticationIntegrationTest;
import fr.emploigo.auth.domain.TestPasswords;
import fr.emploigo.support.FakeGoogle;

import org.junit.jupiter.api.BeforeEach;

import java.net.http.HttpResponse;
import java.util.Map;

/** Flows that send an authentication email, each request sending a message of its own. */
abstract class AuthMailIT extends AuthenticationIntegrationTest {
    protected static final String EMAIL = "slow@example.invalid";
    private static final String EXTERNAL_EMAIL = "fixture@external.invalid";

    private GoogleBrowser google;
    private String password;

    @BeforeEach
    void mailFixture() {
        password = TestPasswords.random();
    }

    @Override
    protected GoogleBrowser newBrowser() {
        return new GoogleBrowser(port, json);
    }

    private GoogleBrowser pendingExternalGoogleBrowser() throws Exception {
        FakeGoogle.email = EXTERNAL_EMAIL;
        var browser = newBrowser();
        assertThat(browser.callback(browser.start(), false).statusCode())
                .as("given: callback accepted")
                .isEqualTo(302);
        assertThat(browser.pending()).as("given: flow pending").isTrue();
        return browser;
    }

    private void ownerAccount() {
        jdbc.queryForObject(
                """
                INSERT INTO auth_accounts(
                    username, email_key, password_hash, email_verified, enabled)
                VALUES (?, ?, ?, TRUE, TRUE) RETURNING id
                """,
                Long.class,
                EMAIL,
                EMAIL,
                passwords.encode(password));
    }

    protected enum SendingFlow {
        SIGNUP("inscription") {
            @Override
            void given(AuthMailIT test) {}

            @Override
            HttpResponse<String> request(AuthMailIT test) throws Exception {
                return test.newBrowser()
                        .post(
                                "signup",
                                Map.of(
                                        "username",
                                        "slow-demo",
                                        "email",
                                        EMAIL,
                                        "password",
                                        test.password,
                                        "confirmation",
                                        test.password,
                                        "acceptedTerms",
                                        true,
                                        "rememberMe",
                                        false));
            }

            @Override
            void permitNextMail(AuthMailIT test) {
                test.permitNextMail("registration-mail", EMAIL);
            }
        },
        RECOVERY("récupération du mot de passe") {
            @Override
            void given(AuthMailIT test) {
                test.ownerAccount();
            }

            @Override
            HttpResponse<String> request(AuthMailIT test) throws Exception {
                return test.newBrowser().post("password/forgot", Map.of("email", EMAIL));
            }

            @Override
            void permitNextMail(AuthMailIT test) {
                test.permitNextMail("recovery-mail", EMAIL);
            }
        },
        GOOGLE_EMAIL("confirmation de l'email de Google") {
            @Override
            void given(AuthMailIT test) throws Exception {
                test.google = test.pendingExternalGoogleBrowser();
            }

            @Override
            HttpResponse<String> request(AuthMailIT test) throws Exception {
                return test.google.post("google/email", Map.of());
            }

            @Override
            void permitNextMail(AuthMailIT test) {
                test.permitNextMail("google-mail", EXTERNAL_EMAIL);
            }
        };

        private final String label;

        SendingFlow(String label) {
            this.label = label;
        }

        /** The state of the flow before its first request: an account, a pending Google flow. */
        abstract void given(AuthMailIT test) throws Exception;

        /** A request of the flow: each one is a new request. */
        abstract HttpResponse<String> request(AuthMailIT test) throws Exception;

        /**
         * Lets the next request of the flow send an email: the delay between two sends of an
         * address has passed.
         */
        abstract void permitNextMail(AuthMailIT test);

        @Override
        public String toString() {
            return label;
        }
    }
}
