package fr.emploigo.auth.api.registration;

import static fr.emploigo.support.FakeProviders.mailStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import fr.emploigo.auth.application.AuthenticationIntegrationTest;
import fr.emploigo.auth.application.confirmation.ConfirmationTokens;
import fr.emploigo.auth.domain.TestPasswords;
import fr.emploigo.support.Browser;
import fr.emploigo.support.MailCounters;
import fr.emploigo.support.RowLock;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.provider.Arguments;
import org.springframework.beans.factory.annotation.Autowired;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import javax.sql.DataSource;

abstract class RegistrationTestBase extends AuthenticationIntegrationTest {
    protected static final String KNOWN_EMAIL = "known@example.invalid";
    protected static final String NEW_EMAIL = "new@example.invalid";
    protected static final String TAKEN_USERNAME = "deja_pris";
    protected static final String DUPLICATE_EMAIL = "duplicate@example.invalid";
    protected static final String DEMO_EMAIL = "demo@example.invalid";
    protected static final String SHARED_EMAIL = "shared@example.invalid";
    protected static final String BLOCKED_EMAIL = "blocked@example.invalid";

    @Autowired ConfirmationTokens tokens;
    @Autowired DataSource dataSource;
    protected String password;

    @BeforeEach
    void fixture() {
        password = TestPasswords.random();
    }

    protected String registrationPasswordHash(String email) {
        return jdbc.queryForObject(
                "SELECT password_hash FROM auth_registrations WHERE email_key = ?",
                String.class,
                email);
    }

    protected long pendingRegistrations() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM auth_registrations WHERE status = 'PENDING'", Long.class);
    }

    /**
     * The code of the current email of the registration, as the mail of the provider carries it.
     */
    protected String pendingCode(String email) {
        UUID id =
                jdbc.queryForObject(
                        """
                        SELECT v.id FROM auth_verifications v
                        JOIN auth_registrations r ON r.id = v.registration_id
                        WHERE r.email_key = ? AND v.status = 'PENDING'
                        """,
                        UUID.class,
                        email);
        return tokens.code(id);
    }

    protected static String wrongCode(String code) {
        return String.format("%06d", (Integer.parseInt(code) + 1) % 1_000_000);
    }

    protected int codeFailures(String email) {
        return jdbc.queryForObject(
                "SELECT code_failures FROM auth_registrations WHERE email_key = ?",
                Integer.class,
                email);
    }

    protected int attemptsOnCurrentCode(String email) {
        return jdbc.queryForObject(
                """
                SELECT v.failed_attempts FROM auth_verifications v
                JOIN auth_registrations r ON r.id = v.registration_id
                WHERE r.email_key = ? AND v.status = 'PENDING'
                """,
                Integer.class,
                email);
    }

    protected long verifications(String status) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM auth_verifications WHERE status = ?", Long.class, status);
    }

    protected static Map<String, List<String>> stableHeaders(HttpResponse<String> response) {
        var headers = new HashMap<>(response.headers().map());
        headers.remove("date");
        // Each browser gets its own session and registration: compare their presence only.
        headers.computeIfPresent(
                "set-cookie",
                (name, values) ->
                        values.stream().map(value -> value.replaceFirst("=[^;]*", "=")).toList());
        headers.computeIfPresent("registration-id", (name, values) -> List.of("present"));
        return headers;
    }

    /** Every row of the registrations and of their codes: a refusal must leave it unchanged. */
    protected List<Map<String, Object>> snapshot() {
        var rows =
                new ArrayList<>(jdbc.queryForList("SELECT * FROM auth_registrations ORDER BY id"));
        rows.addAll(jdbc.queryForList("SELECT * FROM auth_verifications ORDER BY id"));
        return rows;
    }

    protected void permitNextMail(String email) {
        permitNextMail("registration-mail", email);
    }

    /** Forgets the counters of a scope, as a window that elapsed would. */
    protected void resetQuota(String scope) {
        var keys = redis.keys("auth:limits:" + scope + "*");
        if (keys != null && !keys.isEmpty()) redis.delete(keys);
    }

    protected Map<String, Object> signupBody(String email, String secret, boolean rememberMe) {
        return Map.of(
                "email",
                email,
                "password",
                secret,
                "confirmation",
                secret,
                "acceptedTerms",
                true,
                "rememberMe",
                rememberMe);
    }

    protected Map<String, Object> signupBody(String email, String secret) {
        return signupBody(email, secret, false);
    }

    protected Map<String, Object> signupBody(String email) {
        return signupBody(email, password);
    }

    protected HttpResponse<String> signup(Browser browser, String email) throws Exception {
        return browser.post("signup", signupBody(email));
    }

    protected HttpResponse<String> signup(Browser browser, String email, String secret)
            throws Exception {
        return browser.post("signup", signupBody(email, secret));
    }

    /** The registration of the session, as the tab of its signup asks: no email, no key. */
    protected HttpResponse<String> resend(Browser browser) throws Exception {
        var body = new HashMap<String, Object>();
        body.put("registration", browser.registration);
        return browser.post("signup/resend", body);
    }

    protected static Map<String, Object> confirmBody(Browser browser, String code) {
        var body = new HashMap<String, Object>();
        body.put("code", code);
        body.put("registration", browser.registration);
        return body;
    }

    protected HttpResponse<String> confirm(Browser browser, String code) throws Exception {
        return browser.post("signup/confirm", confirmBody(browser, code));
    }

    /**
     * A browser whose signup for {@code email} the next signup replaces: it holds a page with a
     * code of its own, that no longer holds the email. The next mail to the address is permitted.
     */
    protected Browser replacedPage(String email) throws Exception {
        var browser = newBrowser();
        var signup = signup(browser, email, TestPasswords.random());
        assertThat(signup.statusCode()).as("given: signup to be replaced").isEqualTo(202);
        permitNextMail(email);
        return browser;
    }

    protected HttpResponse<String> login(Browser browser, String identifier, String secret)
            throws Exception {
        return browser.post("login", Map.of("identifier", identifier, "password", secret));
    }

    protected HttpResponse<String> login(Browser browser, String identifier) throws Exception {
        return login(browser, identifier, password);
    }

    protected String sessionUsername(Browser browser) throws Exception {
        var session = browser.get("session");
        assertThat(session.statusCode()).as("session status").isEqualTo(200);
        var username = json.readTree(session.body()).path("username");
        return username.isNull() ? null : username.asString();
    }

    protected static long retryAfter(HttpResponse<String> response) {
        return Long.parseLong(response.headers().firstValue("Retry-After").orElseThrow());
    }

    protected String pendingSignup(Browser browser, String email) throws Exception {
        return pendingSignup(browser, email, false);
    }

    protected String pendingSignup(Browser browser, String email, boolean rememberMe)
            throws Exception {
        assertThat(browser.post("signup", signupBody(email, password, rememberMe)).statusCode())
                .as("given: signup accepted")
                .isEqualTo(202);
        return pendingCode(email);
    }

    /**
     * Asks for a new code the way a browser does once the cooldown of the address has elapsed.
     */
    protected String resentCode(Browser browser, String email) throws Exception {
        resetQuota("registration-mail");
        assertThat(resend(browser).statusCode()).as("given: resend accepted").isEqualTo(202);
        return pendingCode(email);
    }

    protected List<Integer> wrongAttempts(Browser browser, String email, int times)
            throws Exception {
        String wrong = wrongCode(pendingCode(email));
        return browser.postStatuses("signup/confirm", confirmBody(browser, wrong), times);
    }

    /**
     * Fails three codes in turn: five attempts on the first two (the fifth is refused as too many),
     * four on the third. Returns the answer to the fifteenth failure of the registration.
     */
    protected HttpResponse<String> failThreeCodes(Browser browser, String email)
            throws Exception {
        resetQuota("confirmation");
        wrongAttempts(browser, email, 5);
        resentCode(browser, email);
        wrongAttempts(browser, email, 5);
        resentCode(browser, email);
        wrongAttempts(browser, email, 4);
        return confirm(browser, wrongCode(pendingCode(email)));
    }

    protected Browser blockedRegistration() throws Exception {
        var browser = newBrowser();
        pendingSignup(browser, BLOCKED_EMAIL);
        assertThat(failThreeCodes(browser, BLOCKED_EMAIL).statusCode())
                .as("given: fifteenth failure")
                .isEqualTo(429);
        return browser;
    }

    protected void existingAccount() {
        jdbc.update(
                """
                INSERT INTO auth_accounts(
                    username, email_key, password_hash, email_verified, enabled)
                VALUES (?, ?, 'fixture-not-usable', TRUE, TRUE)
                """,
                TAKEN_USERNAME,
                KNOWN_EMAIL);
    }

    protected List<HttpResponse<String>> attempts(Browser browser, String code, int times)
            throws Exception {
        var responses = new ArrayList<HttpResponse<String>>();
        for (int i = 0; i < times; i++) responses.add(confirm(browser, code));
        return responses;
    }

    /** Status, rounded wait and body of each answer: what a client sees of it. */
    protected static List<String> answers(List<HttpResponse<String>> responses) {
        return responses.stream()
                .map(
                        response ->
                                response.statusCode()
                                        + " "
                                        + response.headers()
                                                .firstValue("Retry-After")
                                                .map(
                                                        value ->
                                                                Long.toString(
                                                                        Long.parseLong(value) / 60))
                                                .orElse("-")
                                        + " "
                                        + response.body())
                .toList();
    }

    static Stream<Arguments> loginsOfAPendingRegistration() {
        return Stream.of(
                argumentSet("mot de passe de l'inscription", "demo@example.invalid", true),
                argumentSet("mauvais mot de passe", "demo@example.invalid", false),
                argumentSet("pseudo de l'inscription", "resume-demo", true));
    }

    protected record RefusedSend(
            Browser browser,
            Map<String, Object> body,
            String email,
            HttpResponse<String> response,
            MailCounters mail) {}

    /** First signup while the email provider answers 503: the outcome of the send is uncertain. */
    protected RefusedSend refusedSend() throws Exception {
        var browser = newBrowser();
        String email = "retry@example.invalid";
        var body = signupBody(email);
        var mail = new MailCounters();
        mailStatus = 503;
        var response = browser.post("signup", body);
        mailStatus = 200;
        return new RefusedSend(browser, body, email, response, mail);
    }

    static Stream<Arguments> mailboxes() {
        return Stream.of(
                argumentSet("adresse d'un compte existant", KNOWN_EMAIL),
                argumentSet("adresse nouvelle", NEW_EMAIL));
    }

    /** One password per refusal of the rule: the other criteria are judged by the unit tests. */
    static Stream<Arguments> passwordsBreakingTheRule() {
        return Stream.of(
                argumentSet("sans majuscule", "abcdef1!", "WEAK_PASSWORD"),
                argumentSet("7 caractères", "Ab1!xyz", "INVALID_INPUT"));
    }

    static Stream<Arguments> signupAddresses() {
        return Stream.of(
                argumentSet("adresse déjà inscrite", KNOWN_EMAIL),
                argumentSet("adresse nouvelle", NEW_EMAIL));
    }

    static Stream<Arguments> providerOutcomes() {
        return Stream.of(
                argumentSet("fournisseur disponible, adresse déjà inscrite", 200, 202, KNOWN_EMAIL),
                argumentSet("fournisseur disponible, adresse nouvelle", 200, 202, NEW_EMAIL),
                argumentSet("fournisseur en échec, adresse déjà inscrite", 503, 503, KNOWN_EMAIL),
                argumentSet("fournisseur en échec, adresse nouvelle", 503, 503, NEW_EMAIL));
    }

    protected List<HttpResponse<String>> parallelWrongConfirmationsHeldByTheRow(
            Browser browser, String email, int times) throws Exception {
        UUID registration =
                jdbc.queryForObject(
                        "SELECT id FROM auth_registrations WHERE email_key = ?", UUID.class, email);
        String wrong = wrongCode(pendingCode(email));
        try (var pool = Executors.newVirtualThreadPerTaskExecutor();
                var lock = RowLock.hold(dataSource, "auth_registrations", registration)) {
            var futures =
                    IntStream.range(0, times)
                            .mapToObj(
                                    index ->
                                            pool.submit(
                                                    () ->
                                                            confirm(browser.copy(), wrong)))
                            .toList();
            // Each confirmation waits for the row the test holds.
            RowLock.awaitWaiters(jdbc, times, Duration.ofSeconds(10));
            lock.release();
            return futures.stream()
                    .map(unchecked(future -> future.get(10, TimeUnit.SECONDS)))
                    .toList();
        }
    }
}
