package fr.emploigo.auth.api;

import static fr.emploigo.support.FakeGoogle.TOKEN_CALLS;
import static fr.emploigo.support.FakeGoogle.TOKEN_RESPONSE;
import static fr.emploigo.support.FakeProviders.MAIL_BODY;
import static fr.emploigo.support.FakeProviders.MAIL_RESPONSE;

import static org.assertj.core.api.Assertions.assertThat;

import fr.emploigo.auth.application.AuthenticationIntegrationTest;
import fr.emploigo.auth.domain.TestPasswords;
import fr.emploigo.support.FakeGoogle;
import fr.emploigo.support.MailCounters;
import fr.emploigo.support.Stall;


import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.context.TestPropertySource;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

/**
 * The application's reaction when Resend or Google answers after its delay. This class has its own
 * Spring context: a provider client fixes its delays when built, and shorter delays in the shared
 * context would break tests that pause a provider longer. The keys of Google are tested by {@code
 * SpringSecurityGoogleSignInClientKeySetTimeoutTest}.
 */
@TestPropertySource(
        properties = {
            "emploi-go.registration.mail-response-timeout=500ms",
            "emploi-go.registration.mail-connect-timeout=500ms",
            "emploi-go.google.response-timeout=500ms"
        })
class ProviderTimeoutIT extends AuthenticationIntegrationTest {
    private static final String EMAIL = "slow@example.invalid";
    private static final String EXTERNAL_EMAIL = "fixture@external.invalid";

    private GoogleBrowser browser;
    private String password;
    private GoogleBrowser google;

    @BeforeEach
    void fixture() {
        browser = newBrowser();
        password = TestPasswords.random();
    }

    /**
     * Runs {@code request} while the provider holds its answer: the application gives up on its own
     * delay first.
     */
    private HttpResponse<String> whileTheProviderIsSlow(
            Stall stall, Callable<HttpResponse<String>> request) throws Exception {
        try (var hold = stall.hold()) {
            var response = request.call();
            hold.awaitRequests(1);
            assertThat(hold.expired())
                    .as("hold ended by the fixture limit, not by the client delay")
                    .isFalse();
            assertThat(hold.requests()).as("requests held by the provider").isEqualTo(1);
            return response;
        }
    }

    private long ownerAccount() {
        return jdbc.queryForObject(
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

    private long pending(String table) {
        return jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM %s WHERE status = 'PENDING'
                """
                        .formatted(table),
                Long.class);
    }

    private HttpResponse<String> recoveryOf(String email) throws Exception {
        return newBrowser().post("password/forgot", Map.of("email", email));
    }

    private HttpResponse<String> signup() throws Exception {
        return browser.post(
                "signup",
                Map.of(
                        "username",
                        "slow-demo",
                        "email",
                        EMAIL,
                        "password",
                        password,
                        "confirmation",
                        password,
                        "acceptedTerms",
                        true,
                        "rememberMe",
                        false));
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

    @Override
    protected GoogleBrowser newBrowser() {
        return new GoogleBrowser(port, json);
    }

    private enum MailFlow {
        SIGNUP("inscription") {
            @Override
            void given(ProviderTimeoutIT test) {}

            @Override
            HttpResponse<String> request(ProviderTimeoutIT test) throws Exception {
                return test.signup();
            }

            @Override
            long kept(ProviderTimeoutIT test) {
                return test.pending("auth_registrations");
            }

            @Override
            void permitNextMail(ProviderTimeoutIT test) {
                test.permitNextMail("registration-mail", EMAIL);
            }
        },
        RECOVERY("récupération du mot de passe") {
            @Override
            void given(ProviderTimeoutIT test) {
                test.ownerAccount();
            }

            @Override
            HttpResponse<String> request(ProviderTimeoutIT test) throws Exception {
                return test.browser.post("password/forgot", Map.of("email", EMAIL));
            }

            @Override
            long kept(ProviderTimeoutIT test) {
                return test.pending("auth_password_resets");
            }

            @Override
            void permitNextMail(ProviderTimeoutIT test) {
                test.permitNextMail("recovery-mail", EMAIL);
            }
        },
        GOOGLE_EMAIL("confirmation de l'email de Google") {
            @Override
            void given(ProviderTimeoutIT test) throws Exception {
                test.google = test.pendingExternalGoogleBrowser();
            }

            @Override
            HttpResponse<String> request(ProviderTimeoutIT test) throws Exception {
                return test.google.post("google/email", Map.of());
            }

            @Override
            long kept(ProviderTimeoutIT test) {
                return test.pending("auth_google_verifications");
            }

            @Override
            void permitNextMail(ProviderTimeoutIT test) {
                test.permitNextMail("google-mail", EXTERNAL_EMAIL);
            }
        };

        private final String label;

        MailFlow(String label) {
            this.label = label;
        }

        abstract void given(ProviderTimeoutIT test) throws Exception;

        abstract HttpResponse<String> request(ProviderTimeoutIT test) throws Exception;

        /** Requests of the flow still waiting for their email, which a retry can complete. */
        abstract long kept(ProviderTimeoutIT test);

        abstract void permitNextMail(ProviderTimeoutIT test);

        @Override
        public String toString() {
            return label;
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(MailFlow.class)
    void mailProviderAnsweringAfterTheDelayAnswers503AndKeepsTheRequestForARetry(MailFlow flow)
            throws Exception {
        flow.given(this);
        long accounts = count("auth_accounts");
        var mail = new MailCounters();

        var response = whileTheProviderIsSlow(MAIL_RESPONSE, () -> flow.request(this));

        assertThat(response.statusCode()).as("status").isEqualTo(503);
        assertThat(code(response)).as("error code").isEqualTo("UNAVAILABLE");
        assertThat(flow.kept(this)).as("requests kept for a retry").isEqualTo(1);
        assertThat(count("auth_accounts")).as("accounts").isEqualTo(accounts);
        assertThat(mail.calls()).as("provider calls").isEqualTo(1);
    }

    @Test
    void retryAfterMailProviderTimeoutSendsANewMessage() throws Exception {
        var flow = MailFlow.SIGNUP;
        flow.given(this);
        var mail = new MailCounters();
        var slow = whileTheProviderIsSlow(MAIL_RESPONSE, () -> flow.request(this));
        assertThat(slow.statusCode()).as("given: slow provider").isEqualTo(503);
        flow.permitNextMail(this);

        var retry = flow.request(this);

        assertThat(retry.statusCode()).as("status").isEqualTo(202);
        assertThat(mail.sends()).as("distinct messages, the lost answer included").isEqualTo(2);
    }

    @Test
    void mailProviderStoppingAfterItsHeadersAnswers503() throws Exception {
        var flow = MailFlow.SIGNUP;
        flow.given(this);

        var response = whileTheProviderIsSlow(MAIL_BODY, () -> flow.request(this));

        assertThat(response.statusCode()).as("status").isEqualTo(503);
        assertThat(code(response)).as("error code").isEqualTo("UNAVAILABLE");
    }

    @Test
    void sendsWhoseBodyStopsAnswer503AndDoNotBlockTheNextSend() throws Exception {
        List<Integer> stopped;
        boolean expired;

        // Two sends wait for a response whose body stops after its headers.
        try (var pool = Executors.newVirtualThreadPerTaskExecutor();
                var hold = MAIL_BODY.hold()) {
            var first = pool.submit(() -> recoveryOf("premier@example.invalid"));
            var second = pool.submit(() -> recoveryOf("second@example.invalid"));
            hold.awaitRequests(2);
            stopped = List.of(first.get().statusCode(), second.get().statusCode());
            expired = hold.expired();
        }
        var next = recoveryOf("suivant@example.invalid");

        assertThat(expired)
                .as("hold ended by the fixture limit, not by the bound of the exchange")
                .isFalse();
        assertThat(stopped).as("statuses of the sends that stopped").containsOnly(503);
        assertThat(next.statusCode()).as("status of the next send").isEqualTo(202);
    }

    @Test
    void callbackWithTokenEndpointAnsweringAfterTheDelayReportsUnavailableAndNoAccount()
            throws Exception {
        var google = newBrowser();
        var flow = google.start();
        int tokenCalls = TOKEN_CALLS.get();

        var callback = whileTheProviderIsSlow(TOKEN_RESPONSE, () -> google.callback(flow, false));

        assertThat(callback.statusCode()).as("status").isEqualTo(302);
        assertThat(google.errorCode()).as("error code").isEqualTo("UNAVAILABLE");
        assertThat(TOKEN_CALLS.get() - tokenCalls).as("token endpoint calls").isEqualTo(1);
        assertThat(count("auth_google_pending")).as("pending flows").isZero();
        assertThat(count("auth_accounts")).as("accounts").isZero();
        assertThat(count("auth_google_identities")).as("linked identities").isZero();
    }

    @Test
    void newFlowAfterTokenEndpointTimeoutCreatesOneAccount() throws Exception {
        var google = newBrowser();
        var slow =
                whileTheProviderIsSlow(
                        TOKEN_RESPONSE, () -> google.callback(google.start(), false));
        assertThat(slow.statusCode()).as("given: slow token endpoint").isEqualTo(302);

        var retry = google.callback(google.start(), false);
        var signup = google.post("google/signup", Map.of("username", "Jeune"));

        assertThat(retry.statusCode()).as("callback status").isEqualTo(302);
        assertThat(signup.statusCode()).as("signup status").isEqualTo(200);
        assertThat(count("auth_accounts")).as("accounts").isEqualTo(1);
        assertThat(count("auth_google_identities")).as("linked identities").isEqualTo(1);
    }
}
