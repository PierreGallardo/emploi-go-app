package fr.emploigo.auth.api.registration;

import static fr.emploigo.support.FakeProviders.MAIL_RESPONSE;
import static fr.emploigo.support.HostCookies.RECONNECTION_COOKIE;
import static fr.emploigo.support.HostCookies.SESSION_COOKIE;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import fr.emploigo.auth.domain.AuthenticationSecrets;
import fr.emploigo.support.RowLock;
import fr.emploigo.support.SqlFailure;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

class RegistrationConfirmationIT extends RegistrationTestBase {
    @FunctionalInterface
    private interface Scenario {
        void run() throws Exception;
    }

    /** What the application logged, at any level, while the scenario ran. */
    private List<String> logged(Scenario scenario) throws Exception {
        var root =
                (ch.qos.logback.classic.Logger)
                        LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        root.addAppender(appender);
        try {
            scenario.run();
            return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        } finally {
            root.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void rightCodeOpensASessionAndActivatesTheAccount() throws Exception {
        var page = newBrowser();
        String code = pendingSignup(page, "jeune@example.invalid");

        var confirmation = confirm(page, code);

        assertThat(confirmation.statusCode()).as("status").isEqualTo(200);
        assertThat(page.cookies).as("cookies").containsKey(SESSION_COOKIE);
        assertThat(sessionUsername(page)).as("username of the session").isNull();
        assertThat(
                        jdbc.queryForObject(
                                """
                                SELECT enabled AND email_verified FROM auth_accounts
                                WHERE email_key = 'jeune@example.invalid'
                                """,
                                Boolean.class))
                .as("account active")
                .isTrue();
        assertThat(jdbc.queryForObject("SELECT status FROM auth_registrations", String.class))
                .as("registration status")
                .isEqualTo("CONFIRMED");
    }

    @Test
    void confirmationInTheBrowserOfTheSignupRotatesTheSession() throws Exception {
        var page = newBrowser();
        String code = pendingSignup(page, "jeune@example.invalid");
        String anonymous = page.cookies.get(SESSION_COOKIE);

        var confirmation = confirm(page, code);

        assertThat(confirmation.statusCode()).as("status").isEqualTo(200);
        assertThat(page.cookies.get(SESSION_COOKIE))
                .as("session cookie")
                .isNotEqualTo(anonymous);
        assertThat(sessionUsername(page)).as("username").isNull();
    }

    @Test
    void csrfTokenFromBeforeConfirmationIsRefusedOnLogout() throws Exception {
        var page = newBrowser();
        String staleCsrf = page.csrf();
        String code = pendingSignup(page, "jeune@example.invalid");
        assertThat(confirm(page, code).statusCode())
                .as("given: confirmation")
                .isEqualTo(200);

        var logout = page.post("logout", Map.of(), staleCsrf);

        assertThat(logout.statusCode()).isEqualTo(403);
    }

    @Test
    void wrongCodeIsRefusedAsInvalidAndCountsOnTheCodeAndTheRegistration() throws Exception {
        var page = newBrowser();
        String code = pendingSignup(page, DEMO_EMAIL);

        var wrong = confirm(page, wrongCode(code));

        assertThat(wrong.statusCode()).as("status").isEqualTo(400);
        assertThat(code(wrong)).as("error code").isEqualTo("INVALID_CODE");
        assertThat(attemptsOnCurrentCode(DEMO_EMAIL)).as("attempts on the code").isEqualTo(1);
        assertThat(codeFailures(DEMO_EMAIL)).as("failures of the registration").isEqualTo(1);
        assertThat(count("auth_accounts")).as("accounts").isZero();
    }

    @Test
    void textThatIsNotACodeIsRefusedAsInvalidWithoutCountingAnAttempt() throws Exception {
        var page = newBrowser();
        pendingSignup(page, DEMO_EMAIL);
        var before = snapshot();

        var refused = confirm(page, "12ab56");

        assertThat(refused.statusCode()).as("status").isEqualTo(400);
        assertThat(code(refused)).as("error code").isEqualTo("INVALID_CODE");
        assertThat(snapshot()).as("rows").isEqualTo(before);
    }

    /** How a code gets past the time or the server key that it was issued under. */
    @FunctionalInterface
    private interface Ageing {
        void apply(RegistrationConfirmationIT test) throws Exception;
    }

    static Stream<Arguments> expiries() {
        return Stream.of(
                argumentSet(
                        "validité de 15 minutes dépassée",
                        (Ageing)
                                test ->
                                        test.clock.now =
                                                test.clock.now.plus(Duration.ofMinutes(16))),
                argumentSet(
                        "clé des codes tournée",
                        (Ageing)
                                test ->
                                        test.jdbc.update(
                                                "UPDATE auth_verifications SET key_id = ?",
                                                "0".repeat(64))));
    }

    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("expiries")
    void codeOfAnExpiredRegistrationIsRefusedWithCodeExpired(Ageing ageing) throws Exception {
        var page = newBrowser();
        String code = pendingSignup(page, DEMO_EMAIL);
        ageing.apply(this);

        var refused = confirm(page, code);

        assertThat(refused.statusCode()).as("status").isEqualTo(410);
        assertThat(code(refused)).as("error code").isEqualTo("CODE_EXPIRED");
        assertThat(count("auth_accounts")).as("accounts").isZero();
    }

    @Test
    void codeReplacedByAResendIsRefusedWithCodeReplacedAndTheNewOneConfirms() throws Exception {
        var page = newBrowser();
        String old = pendingSignup(page, DEMO_EMAIL);
        String current = resentCode(page, DEMO_EMAIL);

        var replaced = confirm(page, old);
        var confirmation = confirm(page, current);

        assertThat(current).as("code issued by the resend").isNotEqualTo(old);
        assertThat(replaced.statusCode()).as("status of the old code").isEqualTo(410);
        assertThat(code(replaced)).as("error code").isEqualTo("CODE_REPLACED");
        assertThat(confirmation.statusCode()).as("status of the new code").isEqualTo(200);
    }

    @Test
    void fiveFailedAttemptsExhaustTheCodeEvenForTheRightOne() throws Exception {
        var page = newBrowser();
        String code = pendingSignup(page, DEMO_EMAIL);

        var attempts = attempts(page, wrongCode(code), 5);
        var right = confirm(page, code);

        assertThat(attempts.stream().map(HttpResponse::statusCode))
                .as("statuses of the attempts")
                .containsExactly(400, 400, 400, 400, 410);
        assertThat(code(attempts.getLast()))
                .as("code of the fifth")
                .isEqualTo("CODE_ATTEMPTS_EXCEEDED");
        assertThat(right.statusCode()).as("status of the right code").isEqualTo(410);
        assertThat(code(right))
                .as("error code of the right code")
                .isEqualTo("CODE_ATTEMPTS_EXCEEDED");
        assertThat(count("auth_accounts")).as("accounts").isZero();
    }

    @Test
    void resendAfterTooManyAttemptsGivesANewCodeThatConfirms() throws Exception {
        var page = newBrowser();
        pendingSignup(page, DEMO_EMAIL);
        wrongAttempts(page, DEMO_EMAIL, 5);
        String fresh = resentCode(page, DEMO_EMAIL);

        var confirmation = confirm(page, fresh);

        assertThat(confirmation.statusCode()).as("status").isEqualTo(200);
        assertThat(count("auth_accounts")).as("accounts").isEqualTo(1);
    }

    @Test
    void resendReplacesThePreviousCodeAndKeepsTheFailuresOfTheRegistration() throws Exception {
        var page = newBrowser();
        pendingSignup(page, DEMO_EMAIL);
        wrongAttempts(page, DEMO_EMAIL, 2);

        resentCode(page, DEMO_EMAIL);

        assertThat(codeFailures(DEMO_EMAIL)).as("failures of the registration").isEqualTo(2);
        assertThat(attemptsOnCurrentCode(DEMO_EMAIL)).as("attempts on the new code").isZero();
        assertThat(verifications("REPLACED")).as("replaced codes").isEqualTo(1);
        assertThat(verifications("PENDING")).as("pending codes").isEqualTo(1);
    }

    @Test
    void simultaneousConfirmationsOfOneCodeOpenOneSessionAndRefuseTheOtherAsUsed()
            throws Exception {
        var page = newBrowser();
        String code = pendingSignup(page, DEMO_EMAIL);
        UUID registration = jdbc.queryForObject("SELECT id FROM auth_registrations", UUID.class);
        // Two tabs of the browser of the signup.
        var first = page.copy();
        var second = page.copy();

        var responses =
                overlappingAtTheRow(
                        "auth_registrations",
                        registration,
                        () -> confirm(first, code),
                        () -> confirm(second, code));

        var statuses = responses.stream().map(HttpResponse::statusCode).toList();
        var loser = responses.stream().filter(response -> response.statusCode() != 200).findFirst();
        assertThat(statuses).as("statuses").containsExactlyInAnyOrder(200, 410);
        assertThat(code(loser.orElseThrow())).as("error code").isEqualTo("CODE_USED");
        assertThat(count("auth_accounts")).as("accounts").isEqualTo(1);
        assertThat(
                        Stream.of(first, second)
                                .map(unchecked(browser -> browser.get("session").statusCode()))
                                .toList())
                .as("statuses of the sessions of the two tabs")
                .containsExactlyInAnyOrder(200, 401);
    }

    @Test
    void codeIsStoredOnlyAsAKeyedHashAndInNoColumn() throws Exception {
        var page = newBrowser();
        String code = pendingSignup(page, DEMO_EMAIL);
        confirm(page, wrongCode(code));

        var rows =
                jdbc.queryForList(
                        """
                        SELECT concat_ws(' ', token_hash, key_id) FROM auth_verifications
                        UNION ALL
                        SELECT concat_ws(' ', email_key, password_hash, confirmation_key_hash)
                        FROM auth_registrations
                        """,
                        String.class);
        var verification = jdbc.queryForMap("SELECT id, token_hash FROM auth_verifications");

        assertThat(rows).as("rows").isNotEmpty().noneMatch(row -> row.contains(code));
        assertThat(verification.get("token_hash"))
                .as("stored hash")
                .isEqualTo(
                        AuthenticationSecrets.hash(
                                tokens.codeCheck((UUID) verification.get("id"), code)))
                .isNotEqualTo(AuthenticationSecrets.hash(code));
    }

    @Test
    void codeIsNeverLogged() throws Exception {
        var page = newBrowser();
        var codes = new ArrayList<String>();

        var messages =
                logged(
                        () -> {
                            codes.add(pendingSignup(page, DEMO_EMAIL));
                            confirm(page, wrongCode(codes.getFirst()));
                            codes.add(resentCode(page, DEMO_EMAIL));
                            confirm(page, codes.getLast());
                        });

        assertThat(codes).as("codes").hasSize(2);
        assertThat(messages)
                .as("log messages")
                .noneMatch(message -> codes.stream().anyMatch(message::contains));
    }

    @Test
    void accountCreatedMeanwhileForTheEmailAnswers409AndCreatesNoSecondAccount() throws Exception {
        var page = newBrowser();
        String code = pendingSignup(page, DEMO_EMAIL);
        jdbc.update(
                """
                INSERT INTO auth_accounts(
                    username, email_key, password_hash, email_verified, enabled)
                VALUES ('google_owner', ?, NULL, TRUE, TRUE)
                """,
                DEMO_EMAIL);

        var refused = confirm(page, code);

        assertThat(refused.statusCode()).as("status").isEqualTo(409);
        assertThat(code(refused)).as("error code").isEqualTo("ACCOUNT_ALREADY_EXISTS");
        assertThat(count("auth_accounts")).as("accounts").isEqualTo(1);
    }

    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("mailboxes")
    void confirmationOfTheSecondEmailOnTheSamePageAnswers200(String email) throws Exception {
        existingAccount();
        var page = newBrowser();
        assertThat(signup(page, email).statusCode())
                .as("given: signup accepted")
                .isEqualTo(202);
        String nextCode = pendingSignup(page, "next-" + email);

        var confirmation = confirm(page, nextCode);

        assertThat(confirmation.statusCode()).isEqualTo(200);
    }

    @Test
    void pendingRegistrationFromBeforeTheMigrationWithoutKeyIsRefusedAsInvalidCode()
            throws Exception {
        var page = newBrowser();
        String code = pendingSignup(page, DEMO_EMAIL);
        jdbc.update("UPDATE auth_registrations SET confirmation_key_hash = NULL");

        var refused = confirm(page, code);

        assertThat(refused.statusCode()).as("status").isEqualTo(400);
        assertThat(code(refused)).as("error code").isEqualTo("INVALID_CODE");
        assertThat(count("auth_accounts")).as("accounts").isZero();
    }

    @Test
    void failedReconnectionCreationAnswers503AndRollsBackAccountReconnectionAndCode()
            throws Exception {
        var page = newBrowser();
        String email = "rollback@example.invalid";
        String code = pendingSignup(page, email);

        HttpResponse<String> confirmation;
        try (var _ = SqlFailure.inject(jdbc, "auth_reconnections", "BEFORE INSERT")) {
            confirmation = confirm(page, code);
        }

        assertThat(confirmation.statusCode()).as("status").isEqualTo(503);
        assertThat(count("auth_accounts")).as("accounts").isZero();
        assertThat(count("auth_reconnections")).as("reconnections").isZero();
        assertThat(pendingCode(email)).as("pending code").isEqualTo(code);
        assertThat(attemptsOnCurrentCode(email)).as("attempts on the code").isZero();
        assertThat(page.get("session").statusCode()).as("session status").isEqualTo(401);
    }

    @Test
    void explicitRetryAfterFailureAnswers200AndCreatesTheAccount() throws Exception {
        var page = newBrowser();
        String code = pendingSignup(page, "rollback@example.invalid");
        HttpResponse<String> failed;
        try (var _ = SqlFailure.inject(jdbc, "auth_reconnections", "BEFORE INSERT")) {
            failed = confirm(page, code);
        }
        assertThat(failed.statusCode()).as("given: failed confirmation").isEqualTo(503);

        var retry = confirm(page, code);

        assertThat(retry.statusCode()).as("status").isEqualTo(200);
        assertThat(count("auth_accounts")).as("accounts").isEqualTo(1);
    }

    @Test
    void oldCodeIsRefusedAtOnceWhileTheResendMailIsInFlight() throws Exception {
        var page = newBrowser();
        String email = "locked@example.invalid";
        String old = pendingSignup(page, email);
        permitNextMail(email);

        try (var pool = Executors.newVirtualThreadPerTaskExecutor();
                var hold = MAIL_RESPONSE.hold()) {
            var resend =
                    pool.submit(
                            () -> resend(page.copy()).statusCode());
            hold.awaitRequests(1);

            // The resend committed the new code before the call: the old one is refused without
            // waiting for the call.
            var confirmation = confirm(page, old);
            hold.release();

            assertThat(confirmation.statusCode()).as("old confirmation status").isEqualTo(410);
            assertThat(code(confirmation)).as("old confirmation code").isEqualTo("CODE_REPLACED");
            assertThat(resend.get()).as("resend status").isEqualTo(202);
        }

        assertThat(count("auth_accounts")).as("accounts").isZero();
        assertThat(confirm(page, pendingCode(email)).statusCode())
                .as("new code status")
                .isEqualTo(200);
    }

    @ParameterizedTest(name = "rester connecté : {0}")
    @ValueSource(booleans = {true, false})
    void confirmationIssuesTheReconnectionCookieOnlyWhenTheBoxOfTheSignupIsChecked(
            boolean rememberMe) throws Exception {
        var browser = newBrowser();
        String code = pendingSignup(browser, DEMO_EMAIL, rememberMe);

        var response = confirm(browser, code);

        assertThat(response.statusCode()).as("status").isEqualTo(200);
        assertThat(browser.cookies.containsKey(RECONNECTION_COOKIE))
                .as("reconnection cookie kept")
                .isEqualTo(rememberMe);
    }

    @Test
    void newSignupOfTheSameBrowserReplacesTheChoiceOfTheBox() throws Exception {
        var browser = newBrowser();
        pendingSignup(browser, DEMO_EMAIL, true);
        permitNextMail(DEMO_EMAIL);
        String code = pendingSignup(browser, DEMO_EMAIL, false);

        var response = confirm(browser, code);

        assertThat(response.statusCode()).as("status").isEqualTo(200);
        assertThat(browser.cookies).as("cookies").doesNotContainKey(RECONNECTION_COOKIE);
    }
}
