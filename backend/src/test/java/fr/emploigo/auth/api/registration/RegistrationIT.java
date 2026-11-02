package fr.emploigo.auth.api.registration;

import static fr.emploigo.support.FakeProviders.MAIL_CALLS;
import static fr.emploigo.support.FakeProviders.MAIL_PAYLOADS;
import static fr.emploigo.support.FakeProviders.mailStatus;
import static fr.emploigo.support.HostCookies.CSRF_COOKIE;
import static fr.emploigo.support.HostCookies.SESSION_COOKIE;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import fr.emploigo.auth.domain.TestPasswords;
import fr.emploigo.auth.infrastructure.purge.WorkerPurge;
import fr.emploigo.auth.infrastructure.quota.TestQuotaKeys;
import fr.emploigo.support.MailCounters;
import fr.emploigo.support.RowLock;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import java.util.stream.Stream;

class RegistrationIT extends RegistrationTestBase {
    /**
     * A workshop of the Mission Locale: thirty young people sign up behind the same Wi-Fi, so from
     * the same address.
     */
    private static final int WORKSHOP = 30;

    @Autowired AutowireCapableBeanFactory beans;

    @Test
    void signupRacingWithThePurgeInsertsANewRegistrationAndLeavesTheExpiredOne() throws Exception {
        var page = newBrowser();
        pendingSignup(page, DEMO_EMAIL);
        UUID purged = jdbc.queryForObject("SELECT id FROM auth_registrations", UUID.class);
        var again = newBrowser();
        permitNextMail(DEMO_EMAIL);

        HttpResponse<String> signup;
        try (var pool = Executors.newVirtualThreadPerTaskExecutor();
                var lock = RowLock.hold(dataSource, "auth_registrations", purged)) {
            var request = pool.submit(() -> signup(again, DEMO_EMAIL));
            // The signup has read the pending registration and waits for its row.
            RowLock.awaitWaiters(jdbc, 1, Duration.ofSeconds(10));
            lock.execute(
                    """
                    UPDATE auth_registrations
                    SET status = 'EXPIRED', email_key = NULL, password_hash = NULL,
                        confirmation_key_hash = NULL
                    WHERE status = 'PENDING'
                    """);
            lock.commit();
            signup = request.get(10, TimeUnit.SECONDS);
        }

        assertThat(signup.statusCode()).as("status").isEqualTo(202);
        assertThat(count("auth_registrations")).as("registrations").isEqualTo(2);
        assertThat(pendingRegistrations()).as("pending registrations").isEqualTo(1);
        var expired =
                jdbc.queryForMap(
                        """
                        SELECT status, email_key, confirmation_key_hash
                        FROM auth_registrations WHERE id = ?
                        """,
                        purged);
        assertThat(expired.get("status")).as("status of the purged row").isEqualTo("EXPIRED");
        assertThat(expired.get("email_key")).as("email of the purged row").isNull();
        assertThat(expired.get("confirmation_key_hash")).as("key of the purged row").isNull();
        assertThat(confirm(again, pendingCode(DEMO_EMAIL)).statusCode())
                .as("confirmation with the new code")
                .isEqualTo(200);
    }

    @Test
    void signupLeavesRegistrationPendingWithACodeInAnAnonymousSession() throws Exception {
        var browser = newBrowser();

        var signup = signup(browser, "jeune@example.invalid");

        assertThat(signup.statusCode()).as("status").isEqualTo(202);
        assertThat(count("auth_accounts")).as("accounts created").isZero();
        assertThat(verifications("PENDING")).as("pending codes").isEqualTo(1);
        assertThat(browser.cookies.keySet())
                .as("cookies")
                .containsExactlyInAnyOrder(CSRF_COOKIE, SESSION_COOKIE);
        assertThat(browser.get("session").statusCode()).as("session status").isEqualTo(401);
    }

    @Test
    void getOnSignupConfirmIsRefusedAndCreatesNoAccount() throws Exception {
        var page = newBrowser();
        pendingSignup(page, "jeune@example.invalid");

        var confirmation = page.get("signup/confirm");

        assertThat(confirmation.statusCode()).as("status").isEqualTo(401);
        assertThat(count("auth_accounts")).as("accounts created").isZero();
    }

    @Test
    void confirmationEmailCarriesTheCodeAndNoLink() throws Exception {
        var page = newBrowser();
        String code = pendingSignup(page, "mail@example.invalid");

        var payloads = String.join("\n", MAIL_PAYLOADS.values());

        assertThat(payloads).as("email sent").contains(code).doesNotContain("confirmation-email");
    }

    @Test
    void signupAfterReservationExpiryIsAcceptedAndLeavesOnePendingRegistration() throws Exception {
        var browser = newBrowser();
        assertThat(signup(browser, DEMO_EMAIL).statusCode())
                .as("given: first signup")
                .isEqualTo(202);
        clock.now = clock.now.plus(Duration.ofHours(25));
        permitNextMail(DEMO_EMAIL);

        var signup = signup(browser, DEMO_EMAIL);

        assertThat(signup.statusCode()).as("status").isEqualTo(202);
        assertThat(count("auth_accounts")).as("accounts created").isZero();
        assertThat(pendingRegistrations()).as("pending registrations").isEqualTo(1);
    }

    @Test
    void directSendRefusedByProviderAnswers503AndKeepsTheRegistration() throws Exception {
        var refused = refusedSend();

        assertThat(refused.response().statusCode()).as("status").isEqualTo(503);
        assertThat(count("auth_registrations")).as("registrations").isEqualTo(1);
        assertThat(refused.mail().sends()).as("messages sent").isEqualTo(1);
    }

    static Stream<Arguments> firstSignupOutcomes() {
        return Stream.of(
                argumentSet("premier envoi refusé par le fournisseur", 503, 503),
                argumentSet(
                        "premier envoi accepté, nouvelle demande dans le délai entre deux envois",
                        200,
                        202));
    }

    @Test
    void knownAndUnknownEmailsGetSameBodyWhenProviderFails() throws Exception {
        pendingSignup(newBrowser(), DUPLICATE_EMAIL);
        mailStatus = 503;
        var other = newBrowser();
        var unknownMail = new MailCounters();
        var unknown = signup(newBrowser(), "unknown@example.invalid");
        assertThat(unknownMail.calls()).as("provider calls for the unknown email").isEqualTo(1);
        permitNextMail(DUPLICATE_EMAIL);
        var knownMail = new MailCounters();

        var known = signup(other, DUPLICATE_EMAIL);

        assertThat(knownMail.calls()).as("provider calls for the known email").isEqualTo(1);
        assertThat(unknown.statusCode()).as("unknown email status").isEqualTo(503);
        assertThat(known.statusCode()).as("known email status").isEqualTo(503);
        assertThat(known.body()).as("body").isEqualTo(unknown.body());
    }

    @Test
    void workshopOfThirtyYoungPeopleSignsUpFromTheSameAddressWithoutAnyRefusal() throws Exception {
        var statuses =
                IntStream.range(0, WORKSHOP)
                        .boxed()
                        .map(
                                unchecked(
                                        i ->
                                                signup(
                                                                newBrowser(),
                                                                "atelier" + i + "@example.invalid")
                                                        .statusCode()))
                        .toList();

        assertThat(statuses).as("statuses of the signups").hasSize(WORKSHOP).containsOnly(202);
        assertThat(MAIL_CALLS.get()).as("emails sent").isEqualTo(WORKSHOP);
        // The room is one address, and every signup counted against it: the limit is wide, not
        // absent.
        assertThat(
                        redis.opsForValue()
                                .get(
                                        "auth:limits:registration-mail:ip:"
                                                + TestQuotaKeys.KEYS.address("127.0.0.1")))
                .as("attempts of the address")
                .isEqualTo(Integer.toString(WORKSHOP));
    }

    @Test
    void cooldownOfTheEmailRefusesResendsBeforeTheProviderWithoutCostingASend() throws Exception {
        var browser = newBrowser();
        String email = "quota@example.invalid";
        assertThat(browser.post("signup", signupBody(email)).statusCode())
                .isEqualTo(202);
        assertThat(resend(browser).statusCode())
                .isEqualTo(429);
        assertThat(MAIL_CALLS.get()).isEqualTo(1);
        permitNextMail(email);
        assertThat(resend(browser).statusCode())
                .isEqualTo(202);
        permitNextMail(email);
        assertThat(resend(browser).statusCode())
                .as("third send: the resend refused by the cooldown cost none")
                .isEqualTo(202);
        permitNextMail(email);
        assertThat(resend(browser).statusCode())
                .isEqualTo(429);
        assertThat(MAIL_CALLS.get()).as("emails sent").isEqualTo(3);
    }

    @Test
    void countersLostAtRedisDoNotBlockTheResend() throws Exception {
        var browser = newBrowser();
        String email = "lost-counters@example.invalid";
        assertThat(browser.post("signup", signupBody(email)).statusCode())
                .isEqualTo(202);
        assertThat(resend(browser).statusCode())
                .isEqualTo(429);
        redis.delete(redis.keys("auth:limits:*"));

        var resend = resend(browser);

        assertThat(resend.statusCode()).as("status").isEqualTo(202);
    }

    @Test
    void signupsWithSameUsernameDifferingByCaseBothAnswer202BeforeEmailProof() throws Exception {
        var first = signup(newBrowser(), "a@example.invalid");
        var second = signup(newBrowser(), "b@example.invalid");

        assertThat(List.of(first.statusCode(), second.statusCode()))
                .as("statuses")
                .containsExactly(202, 202);
    }

    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("passwordsBreakingTheRule")
    void passwordBreakingTheRuleIsRefusedWithNoEmail(String refused, String code) throws Exception {
        password = refused;
        var mail = new MailCounters();

        var rejected = signup(newBrowser(), "rule@example.invalid");

        assertThat(rejected.statusCode()).as("status").isEqualTo(400);
        assertThat(code(rejected)).as("code").isEqualTo(code);
        assertThat(mail.calls()).as("provider calls").isZero();
    }

    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("providerOutcomes")
    void firstSignupAnswersProviderOutcomeAfterOneProviderCall(
            int providerStatus, int expectedStatus, String email) throws Exception {
        existingAccount();
        var body = signupBody(email);
        mailStatus = providerStatus;
        var mail = new MailCounters();

        var first = newBrowser().post("signup", body);

        assertThat(first.statusCode()).as("status").isEqualTo(expectedStatus);
        assertThat(mail.calls()).as("provider calls").isEqualTo(1);
    }

    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("mailboxes")
    void resendAfterFirstSignupAnswers202AfterOneProviderCall(String email) throws Exception {
        existingAccount();
        var browser = newBrowser();
        var first = browser.post("signup", signupBody(email));
        assertThat(first.statusCode()).as("given: first signup").isEqualTo(202);
        permitNextMail(email);
        var mail = new MailCounters();

        var resend = resend(browser);

        assertThat(resend.statusCode()).as("status").isEqualTo(202);
        assertThat(mail.calls()).as("provider calls").isEqualTo(1);
    }

    /** The form stays on screen after the refusal: the browser signs up again. */
    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("mailboxes")
    void signupRefusedByTheProviderKeepsNoRegistrationInTheBrowser(String email)
            throws Exception {
        existingAccount();
        var browser = newBrowser();
        mailStatus = 503;
        var first = browser.post("signup", signupBody(email));
        mailStatus = 200;
        assertThat(first.statusCode()).as("given: first signup").isEqualTo(503);
        permitNextMail(email);
        var mail = new MailCounters();

        var resend = resend(browser);

        assertThat(resend.statusCode()).as("status").isEqualTo(410);
        assertThat(code(resend)).as("error code").isEqualTo("NO_PENDING_REGISTRATION");
        assertThat(mail.calls()).as("provider calls").isZero();
    }

    @Test
    void signupWithAnotherPasswordWithinTheSendDelayKeepsThePasswordOfThePendingRegistration()
            throws Exception {
        var browser = newBrowser();
        var body = signupBody(DEMO_EMAIL);
        assertThat(browser.post("signup", body).statusCode())
                .as("given: first signup")
                .isEqualTo(202);
        String stored = registrationPasswordHash(DEMO_EMAIL);
        var another = new java.util.HashMap<>(body);
        another.put("password", TestPasswords.random());
        another.put("confirmation", another.get("password"));

        var refused = browser.post("signup", another);

        assertThat(refused.statusCode()).as("status").isEqualTo(429);
        assertThat(code(refused)).as("code").isEqualTo("MAIL_RECENTLY_SENT");
        assertThat(registrationPasswordHash(DEMO_EMAIL))
                .as("password hash of the registration")
                .isEqualTo(stored);
    }

    @Test
    void emailProviderRefusalAnswers503WithoutLeakingProviderDetailAfterOneCall() throws Exception {
        mailStatus = 403;
        var mail = new MailCounters();

        var rejected = signup(newBrowser(), "provider@example.invalid");

        assertThat(rejected.statusCode()).as("status").isEqualTo(503);
        assertThat(rejected.body()).as("body").doesNotContain("owner-fixture@example.invalid");
        assertThat(mail.calls()).as("provider calls").isEqualTo(1);
    }

    @Test
    void repeatAfterThePurgeIsANewRequestAnswering202WithANewEmail() throws Exception {
        var browser = newBrowser();
        assertThat(signup(browser, DEMO_EMAIL).statusCode())
                .as("given: first signup")
                .isEqualTo(202);
        WorkerPurge.runAt(beans, clock, clock.now.plus(Duration.ofHours(25)));
        permitNextMail(DEMO_EMAIL);
        var mail = new MailCounters();

        var repeat = signup(browser, DEMO_EMAIL);

        assertThat(repeat.statusCode()).as("status").isEqualTo(202);
        assertThat(mail.sends()).as("messages sent").isEqualTo(1);
        assertThat(pendingRegistrations()).as("pending registrations").isEqualTo(1);
    }

    @Test
    void repeatAfterTheSendDelayIsANewRequestAnsweringWithANewCode() throws Exception {
        var browser = newBrowser();
        assertThat(signup(browser, DEMO_EMAIL).statusCode())
                .as("given: first signup")
                .isEqualTo(202);
        String firstCode = pendingCode(DEMO_EMAIL);
        permitNextMail(DEMO_EMAIL);
        var mail = new MailCounters();

        var repeat = signup(browser, DEMO_EMAIL);

        assertThat(repeat.statusCode()).as("status").isEqualTo(202);
        assertThat(mail.sends()).as("messages sent").isEqualTo(1);
        assertThat(pendingCode(DEMO_EMAIL)).as("code").isNotEqualTo(firstCode);
    }

    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("firstSignupOutcomes")
    void repeatWithinTheSendDelayAnswers429MailRecentlySentWithoutSendingAgain(
            int providerStatus, int firstStatus) throws Exception {
        var browser = newBrowser();
        var body = signupBody(DEMO_EMAIL);
        mailStatus = providerStatus;
        assertThat(browser.post("signup", body).statusCode())
                .as("given: first signup")
                .isEqualTo(firstStatus);
        mailStatus = 200;
        String initialCode = pendingCode(DEMO_EMAIL);
        var mail = new MailCounters();

        var repeat = browser.post("signup", body);

        assertThat(repeat.statusCode()).as("status").isEqualTo(429);
        assertThat(code(repeat)).as("code").isEqualTo("MAIL_RECENTLY_SENT");
        assertThat(retryAfter(repeat)).as("Retry-After").isBetween(1L, 60L);
        assertThat(mail.calls()).as("provider calls").isZero();
        assertThat(count("auth_registrations")).as("registrations").isEqualTo(1);
        assertThat(pendingCode(DEMO_EMAIL)).as("code").isEqualTo(initialCode);
    }

    @Test
    void repeatResendWithinTheSendDelayAnswers429MailRecentlySentWithoutSendingAgain()
            throws Exception {
        var browser = newBrowser();
        assertThat(signup(browser, DEMO_EMAIL).statusCode())
                .as("given: first signup")
                .isEqualTo(202);
        permitNextMail(DEMO_EMAIL);
        assertThat(resend(browser).statusCode())
                .as("given: first resend")
                .isEqualTo(202);
        String resentCode = pendingCode(DEMO_EMAIL);
        var mail = new MailCounters();

        var repeat = resend(browser);

        assertThat(repeat.statusCode()).as("status").isEqualTo(429);
        assertThat(code(repeat)).as("code").isEqualTo("MAIL_RECENTLY_SENT");
        assertThat(mail.calls()).as("provider calls").isZero();
        assertThat(pendingCode(DEMO_EMAIL)).as("code").isEqualTo(resentCode);
    }

    @Test
    void signupWithAnotherUsernameWithinTheSendDelayIsRefusedWithoutEmail() throws Exception {
        var browser = newBrowser();
        assertThat(signup(browser, DEMO_EMAIL).statusCode())
                .as("given: first signup")
                .isEqualTo(202);
        var mail = new MailCounters();

        var refused = signup(browser, DEMO_EMAIL);

        assertThat(refused.statusCode()).as("status").isEqualTo(429);
        assertThat(code(refused)).as("code").isEqualTo("MAIL_RECENTLY_SENT");
        assertThat(mail.calls()).as("provider calls").isZero();
        assertThat(count("auth_registrations")).as("registrations").isEqualTo(1);
    }

    @Test
    void signupsOverTheVolumeLimitOfTheEmailAreRefusedAsRateLimited() throws Exception {
        var browser = newBrowser();
        assertThat(signup(browser, DEMO_EMAIL).statusCode())
                .as("given: first signup")
                .isEqualTo(202);
        permitNextMail(DEMO_EMAIL);
        assertThat(signup(browser, DEMO_EMAIL).statusCode())
                .as("given: second signup")
                .isEqualTo(202);
        permitNextMail(DEMO_EMAIL);
        assertThat(signup(browser, DEMO_EMAIL).statusCode())
                .as("given: third signup")
                .isEqualTo(202);
        permitNextMail(DEMO_EMAIL);
        var mail = new MailCounters();

        var refused = signup(browser, DEMO_EMAIL);

        assertThat(refused.statusCode()).as("status").isEqualTo(429);
        assertThat(code(refused)).as("code").isEqualTo("RATE_LIMITED");
        assertThat(mail.calls()).as("provider calls").isZero();
    }

    @Test
    void signupAtTheHashingLimitIsRefusedWithoutEmailAndSucceedsOnceFree() throws Exception {
        var browser = newBrowser();
        var body = signupBody(DEMO_EMAIL);
        assertThat(browser.post("signup", body).statusCode())
                .as("given: first signup")
                .isEqualTo(202);
        permitNextMail(DEMO_EMAIL);

        try (var gate = hashingGate()) {
            gate.saturate(hashing.limit());
            var mail = new MailCounters();

            var refused = browser.post("signup", body);

            assertThat(refused.statusCode()).as("status").isEqualTo(503);
            assertThat(refused.headers().firstValue("Retry-After")).as("Retry-After").contains("5");
            assertThat(code(refused)).as("code").isEqualTo("BUSY");
            assertThat(mail.calls()).as("provider calls").isZero();

            gate.releaseAndWait();
            var accepted = browser.post("signup", body);

            assertThat(accepted.statusCode()).as("status once free").isEqualTo(202);
            assertThat(count("auth_registrations")).as("registrations").isEqualTo(1);
        }
    }

    @Test
    void signupFromAnOpenClientThatStillSendsARequestIdIsAccepted() throws Exception {
        var body = new HashMap<String, Object>(signupBody(DEMO_EMAIL));
        body.put("requestId", UUID.randomUUID());

        var response = newBrowser().post("signup", body);

        assertThat(response.statusCode()).as("status").isEqualTo(202);
        assertThat(count("auth_registrations")).as("registrations").isEqualTo(1);
    }
}
