package fr.emploigo.auth.api.session;

import static fr.emploigo.support.HostCookies.RECONNECTABLE_COOKIE;
import static fr.emploigo.support.HostCookies.RECONNECTION_COOKIE;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import fr.emploigo.auth.application.AuthenticationIntegrationTest;
import fr.emploigo.auth.application.account.AccountRepository;
import fr.emploigo.auth.application.failure.AuthenticationFailedException;
import fr.emploigo.auth.application.session.AuthenticationSessions;
import fr.emploigo.auth.application.session.ResumeSessionUseCase;
import fr.emploigo.auth.application.session.SessionPersistence;
import fr.emploigo.auth.domain.Account;
import fr.emploigo.auth.domain.AuthenticationSecrets;
import fr.emploigo.auth.infrastructure.quota.TestQuotaKeys;
import fr.emploigo.support.Browser;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.provider.Arguments;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import javax.sql.DataSource;

abstract class AuthenticationSessionSupport extends AuthenticationIntegrationTest {
    static final String EMAIL = "jeune@example.invalid";

    /**
     * The tolerance of the reuse of a previous secret: its default, which the tests of the context
     * keep.
     */
    static final Duration TOLERANCE = Duration.ofSeconds(30);

    @Autowired AccountRepository accounts;
    @Autowired AuthenticationSessions sessions;
    @Autowired ResumeSessionUseCase resumeSession;
    @Autowired ApplicationContext context;
    @Autowired DataSource dataSource;

    String password;

    long accountId;

    @BeforeEach
    void fixture() {
        password = UUID.randomUUID() + " 🔑 ";
        accountId =
                accounts.save(
                                new Account(
                                        "Ecole_demo",
                                        EMAIL,
                                        passwords.encode(password),
                                        true,
                                        true))
                        .id();
    }

    HttpResponse<String> login(Browser browser, String identifier, String csrf)
            throws Exception {
        return login(browser, identifier, csrf, true);
    }

    HttpResponse<String> login(Browser browser, String identifier, String csrf, boolean rememberMe)
            throws Exception {
        return browser.post(
                "login",
                Map.of("identifier", identifier, "password", password, "rememberMe", rememberMe),
                csrf);
    }

    Browser loggedIn() throws Exception {
        var browser = newBrowser();
        assertThat(login(browser, EMAIL, browser.csrf()).statusCode())
                .as("given: login accepted")
                .isEqualTo(200);
        return browser;
    }

    HttpResponse<String> resume(Browser browser) throws Exception {
        return browser.post("resume", "", browser.csrf());
    }

    HttpResponse<String> logout(Browser browser) throws Exception {
        return browser.post("logout", "", browser.csrf());
    }

    static String indicatorOf(HttpResponse<String> response) {
        return response.headers().allValues("set-cookie").stream()
                .filter(value -> value.startsWith(RECONNECTABLE_COOKIE + "="))
                .findFirst()
                .orElse("");
    }

    static final String RECONNECTION_SECONDS =
            "Max-Age=" + AuthenticationSessions.RECONNECTION_DURATION.toSeconds();

    /**
     * Spring Session computes expiry from the last access and timeout in PostgreSQL, against the
     * system clock.
     */
    void inactiveFor(Duration inactivity) {
        jdbc.update(
                "UPDATE spring_session SET last_access_time = ?",
                System.currentTimeMillis() - inactivity.toMillis());
    }

    void expireTheSessionOfInactivity() {
        inactiveFor(sessionTimeout.plusSeconds(1));
    }

    Instant lastSeenAt() {
        return jdbc.queryForObject(
                        "SELECT last_seen_at FROM auth_accounts WHERE id = ?",
                        Timestamp.class,
                        accountId)
                .toInstant();
    }

    boolean familyRevoked() {
        return Boolean.TRUE.equals(
                jdbc.queryForObject(
                        "SELECT revoked FROM auth_reconnections WHERE account_id = ?",
                        Boolean.class,
                        accountId));
    }

    Browser holding(String reconnectionCookie) {
        var browser = newBrowser();
        browser.cookies.put(RECONNECTION_COOKIE, reconnectionCookie);
        return browser;
    }

    /** The browser of a young person who resumed once, and the secret that the resume replaced. */
    record Rotated(Browser owner, String previous) {}

    Rotated rotatedOnce() throws Exception {
        var owner = loggedIn();
        String previous = owner.cookies.get(RECONNECTION_COOKIE);
        assertThat(resume(owner).statusCode()).as("given: resume accepted").isEqualTo(200);
        return new Rotated(owner, previous);
    }

    /**
     * Time for a request that takes no lock to be answered; the lock held by the test would make it
     * wait longer.
     */
    static final Duration WITHOUT_WAITING = Duration.ofSeconds(5);

    /**
     * Time for a resume to reach the account lock held by the test, and to finish once it is
     * released.
     */
    static final Duration ARRIVAL = Duration.ofSeconds(5);

    static String cookieWithAWrongSecret(UUID family) {
        return family + "." + AuthenticationSecrets.generate();
    }

    String bodyOfTheRefusalOfAnUnknownFamily() throws Exception {
        var unknown = newBrowser();
        unknown.cookies.put(RECONNECTION_COOKIE, cookieWithAWrongSecret(UUID.randomUUID()));
        return resume(unknown).body();
    }

    static void assertRefused(
            java.util.concurrent.Future<?> resuming, AuthenticationFailedException.Reason reason) {
        assertThatThrownBy(() -> resuming.get(ARRIVAL.toSeconds(), TimeUnit.SECONDS))
                .as("resume")
                .hasRootCauseInstanceOf(AuthenticationFailedException.class)
                .rootCause()
                .isInstanceOfSatisfying(
                        AuthenticationFailedException.class,
                        refusal -> assertThat(refusal.reason()).as("reason").isEqualTo(reason));
    }

    static final String ROTATION =
            "UPDATE auth_reconnections SET previous_hash = secret_hash, secret_hash = 'rotated'";

    static final String REVOCATION = "UPDATE auth_reconnections SET revoked = TRUE";

    static final String EXPIRY =
            "UPDATE auth_reconnections SET expires_at = expires_at - INTERVAL '31 days'";

    static String failuresKey(String loginKey) {
        return "auth:limits:login:failures:" + TestQuotaKeys.KEYS.identity(loginKey);
    }

    static String delayKey(String loginKey) {
        return "auth:limits:login:delay:" + TestQuotaKeys.KEYS.identity(loginKey);
    }

    static String addressKey() {
        return "auth:limits:ip:" + TestQuotaKeys.KEYS.address("127.0.0.1");
    }

    List<Integer> wrongPasswords(String identifier, int times) throws Exception {
        var browser = newBrowser();
        String body =
                json.writeValueAsString(
                        Map.of("identifier", identifier, "password", "not-" + password));
        return browser.postStatuses("login", body, times);
    }

    HttpResponse<String> wrongPassword(String identifier) throws Exception {
        var browser = newBrowser();
        return browser.post(
                "login",
                json.writeValueAsString(
                        Map.of("identifier", identifier, "password", "not-" + password)));
    }

    Browser browserOfANewAccount(int number) {
        var account =
                accounts.save(
                        new Account(
                                "jeune_" + number,
                                "jeune-" + number + "@example.invalid",
                                "unused-fixture-hash",
                                true,
                                true));
        var browser = newBrowser();
        browser.cookies.put(
                RECONNECTION_COOKIE,
                sessions.create(account.id(), SessionPersistence.REMEMBERED, null, null)
                        .reconnectionCookie());
        return browser;
    }

    /**
     * Value of each counter of the quota; the remaining time of a counter is left out, as it
     * decreases.
     */
    Map<String, String> quotaCounters() {
        return redis.keys("auth:limits:*").stream()
                .collect(Collectors.toMap(key -> key, key -> redis.opsForValue().get(key)));
    }

    String resumeCounterKey(long account) {
        UUID family =
                jdbc.queryForObject(
                        "SELECT id FROM auth_reconnections WHERE account_id = ?",
                        UUID.class,
                        account);
        return "auth:limits:resume:identity:" + TestQuotaKeys.KEYS.identity(family.toString());
    }

    List<Integer> resumesEvery29Days(Browser browser, int times) {
        return IntStream.range(0, times)
                .boxed()
                .map(
                        unchecked(
                                _ -> {
                                    clock.now = clock.now.plus(Duration.ofDays(29));
                                    // The resume counter lives 15 minutes of real time while the
                                    // test clock moves by days, so it is cleared here.
                                    redis.delete(redis.keys("auth:limits:resume:identity:*"));
                                    return resume(browser).statusCode();
                                }))
                .toList();
    }

    static Stream<Arguments> accountChangesThatRefuseTheReconnection() {
        return Stream.of(
                argumentSet("compte désactivé", "enabled = FALSE"),
                argumentSet(
                        "version des identifiants changée",
                        "credential_version = credential_version + 1"));
    }
}
