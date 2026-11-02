package fr.emploigo.auth.api.google;

import static org.assertj.core.api.Assertions.assertThat;

import fr.emploigo.auth.api.GoogleBrowser;
import fr.emploigo.auth.api.GoogleBrowser.Flow;
import fr.emploigo.auth.application.AuthenticationIntegrationTest;
import fr.emploigo.auth.application.confirmation.ConfirmationTokens;
import fr.emploigo.auth.application.google.GoogleAuthenticationFailedException;
import fr.emploigo.auth.application.google.GoogleTransactions;
import fr.emploigo.auth.application.session.AuthenticationGrant;
import fr.emploigo.auth.application.session.AuthenticationSessions;
import fr.emploigo.auth.domain.AccountIdentifier;
import fr.emploigo.auth.domain.AuthenticationSecrets;
import fr.emploigo.auth.domain.GoogleIdentity;
import fr.emploigo.auth.domain.TestPasswords;
import fr.emploigo.auth.infrastructure.purge.WorkerPurge;
import fr.emploigo.support.AdvisoryLock;
import fr.emploigo.support.Browser;
import fr.emploigo.support.FakeGoogle;
import fr.emploigo.support.FakeProviders;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.transaction.PlatformTransactionManager;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

abstract class GoogleAuthenticationSupport extends AuthenticationIntegrationTest {
    static final String EXTERNAL_EMAIL = "fixture@external.invalid";

    static final Duration PAST_PROOF_DURATION =
            GoogleTransactions.PROOF_DURATION.plusSeconds(1);

    /**
     * A call reaches its lock in under 100 ms; the first one waits for the second, and the Google
     * calls give up after the 3 s {@code lock_timeout}: 1 s keeps 2 s of margin.
     */
    static final Duration LOCK_ARRIVAL = Duration.ofSeconds(1);

    @Autowired ConfirmationTokens tokens;
    @Autowired GoogleTransactions transactions;
    @Autowired AuthenticationSessions sessions;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired DataSource dataSource;
    @Autowired AutowireCapableBeanFactory beans;
    String password;

    @BeforeEach
    void fixture() {
        password = TestPasswords.random();
    }

    @Override
    protected GoogleBrowser newBrowser() {
        return new GoogleBrowser(port, json);
    }

    /**
     * Another browser holding the same cookies as {@code browser}, as another tab or a replay
     * would.
     */
    GoogleBrowser sharingCookiesOf(Browser browser) {
        var copy = newBrowser();
        copy.cookies.putAll(browser.cookies);
        return copy;
    }

    /**
     * A new browser that sends {@code cookies} from its first request, as a robot without session
     * or with a made-up one.
     */
    GoogleBrowser browserHolding(Map<String, String> cookies) {
        var browser = newBrowser();
        browser.cookies.putAll(cookies);
        return browser;
    }

    long account(String name, String address, boolean localPassword) {
        return jdbc.queryForObject(
                """
                INSERT INTO auth_accounts(
                    username, email_key, password_hash, email_verified, enabled)
                VALUES (?, ?, ?, TRUE, TRUE) RETURNING id
                """,
                Long.class,
                AccountIdentifier.username(name),
                address,
                localPassword ? passwords.encode(password) : null);
    }

    void linkIdentity(long accountId) {
        jdbc.update(
                """
                    INSERT INTO auth_google_identities(subject, account_id)
                    VALUES (?, ?)
                    """,
                FakeGoogle.subject,
                accountId);
    }

    long linkedGoogleOwner(String name) {
        long id = account(name, FakeGoogle.email, false);
        linkIdentity(id);
        return id;
    }

    long issuedFlows() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM auth_google_flows WHERE issued_family_id IS NOT NULL",
                Long.class);
    }

    long linkedAccount() {
        return jdbc.queryForObject(
                "SELECT account_id FROM auth_google_identities WHERE subject = ?",
                Long.class,
                FakeGoogle.subject);
    }

    List<Boolean> linkedAccountsWithoutPassword() {
        return jdbc.queryForList(
                """
                SELECT a.password_hash IS NULL FROM auth_accounts a
                JOIN auth_google_identities i ON i.account_id = a.id WHERE i.subject = ?
                """,
                Boolean.class,
                FakeGoogle.subject);
    }

    boolean passwordless(long accountId) {
        return jdbc.queryForObject(
                "SELECT password_hash IS NULL FROM auth_accounts WHERE id = ?",
                Boolean.class,
                accountId);
    }

    long credentialVersion(long accountId) {
        return jdbc.queryForObject(
                "SELECT credential_version FROM auth_accounts WHERE id = ?", Long.class, accountId);
    }

    long activeReconnections(long accountId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM auth_reconnections WHERE account_id = ? AND NOT revoked",
                Long.class,
                accountId);
    }

    long pendingVerifications() {
        return jdbc.queryForObject(
                """
                    SELECT COUNT(*) FROM auth_google_verifications
                    WHERE subject = ? AND status = 'PENDING'
                    """,
                Long.class,
                FakeGoogle.subject);
    }

    String emailLink() {
        UUID id =
                jdbc.queryForObject(
                        """
                            SELECT id FROM auth_google_verifications
                            WHERE subject = ? AND status = 'PENDING'
                            """,
                        UUID.class,
                        FakeGoogle.subject);
        return id + "." + tokens.googleToken(id);
    }

    void permitNextMail(String address) {
        permitNextMail("google-mail", address);
    }

    /**
     * Provider calls since this object was created: relative to the scenario, not a global total.
     */
    static final class Calls {
        private final AtomicInteger counter;
        private final int before;

        Calls(AtomicInteger counter) {
            this.counter = counter;
            this.before = counter.get();
        }

        int count() {
            return counter.get() - before;
        }
    }

    String outcome(String success, Runnable action) {
        try {
            action.run();
            return success;
        } catch (GoogleAuthenticationFailedException failure) {
            return failure.reason().name();
        }
    }

    void accepted(GoogleBrowser browser, Flow flow) throws Exception {
        assertThat(browser.callback(flow, false).statusCode())
                .as("given: callback accepted")
                .isEqualTo(302);
        assertThat(browser.pending()).as("given: flow pending").isTrue();
    }

    Flow pending(GoogleBrowser browser) throws Exception {
        var flow = browser.start();
        accepted(browser, flow);
        return flow;
    }

    GoogleBrowser browserHoldingPreCallbackCookies() throws Exception {
        var browser = newBrowser();
        var flow = browser.start();
        var stale = sharingCookiesOf(browser);
        accepted(browser, flow);
        return stale;
    }

    GoogleBrowser pendingBrowser() throws Exception {
        var browser = newBrowser();
        pending(browser);
        return browser;
    }

    GoogleBrowser pendingExternalBrowser() throws Exception {
        FakeGoogle.email = EXTERNAL_EMAIL;
        return pendingBrowser();
    }

    record SignedUp(GoogleBrowser browser, Flow flow, long accountId) {}

    SignedUp signedUpWithGmail() throws Exception {
        var browser = newBrowser();
        var flow = pending(browser);
        var response = browser.post("google/signup", Map.of("username", "Jeune"));
        assertThat(response.statusCode()).as("given: signup accepted").isEqualTo(200);
        return new SignedUp(browser, flow, linkedAccount());
    }

    /** Starts a new flow 25 hours later: the API purges nothing, the worker does. */
    void newFlowAfter25Hours() {
        clock.now = clock.now.plus(Duration.ofHours(25));
        transactions.start(
                AuthenticationSecrets.generate(), AuthenticationSecrets.generate(), null);
    }

    /** The worker purges 25 hours later: the flows older than a day are gone. */
    void purgeAfter25Hours() {
        WorkerPurge.runAt(beans, clock, clock.now.plus(Duration.ofHours(25)));
    }

    GoogleBrowser loggedInLocalBrowser() throws Exception {
        var browser = newBrowser();
        var response =
                browser.post(
                        "login",
                        Map.of("identifier", "LOCAL", "password", password, "rememberMe", true));
        assertThat(response.statusCode()).as("given: login accepted").isEqualTo(200);
        return browser;
    }

    GoogleBrowser accessVerifiedBrowser() throws Exception {
        var browser = pendingBrowser();
        var response =
                browser.post("google/access", Map.of("identifier", "local", "password", password));
        assertThat(response.statusCode()).as("given: access accepted").isEqualTo(200);
        return browser;
    }

    HttpResponse<String> confirmLink(GoogleBrowser browser) throws Exception {
        return browser.post("google/link", Map.of("confirmed", true));
    }

    HttpResponse<String> linkWhileEmailProviderIsDown(GoogleBrowser browser) throws Exception {
        FakeProviders.mailStatus = 503;
        try {
            return confirmLink(browser);
        } finally {
            FakeProviders.mailStatus = 200;
        }
    }

    void linkedWhileProviderDown(GoogleBrowser browser) throws Exception {
        var response = linkWhileEmailProviderIsDown(browser);
        assertThat(response.statusCode()).as("given: link accepted").isEqualTo(200);
        assertThat(flag(response, "changed")).as("given: link changed").isTrue();
    }

    String requestedEmailLink(GoogleBrowser browser) throws Exception {
        var response = browser.post("google/email", Map.of());
        assertThat(response.statusCode()).as("given: email requested").isEqualTo(202);
        return emailLink();
    }

    GoogleBrowser externalEmailConfirmedElsewhere() throws Exception {
        var link = requestedEmailLink(pendingExternalBrowser());
        var other = newBrowser();
        var response = other.post("google/email/confirm", Map.of("token", link));
        assertThat(response.statusCode()).as("given: email confirmed").isEqualTo(204);
        return other;
    }

    record Accepted(String proof, GoogleTransactions.Callback callback) {}

    /**
     * A Google callback accepted at the application-service level, for the current subject and
     * email.
     */
    Accepted acceptedCallback() {
        String state = AuthenticationSecrets.generate();
        String proof = AuthenticationSecrets.generate();
        transactions.start(state, proof, null);
        var scope = transactions.consume(state, proof, null);
        var callback =
                transactions.accept(
                        new GoogleIdentity(FakeGoogle.subject, FakeGoogle.email, true),
                        scope,
                        null,
                        state,
                        null,
                        null);
        return new Accepted(proof, callback);
    }

    /**
     * A flow replaced by the callback of another Google identity in a second tab of the same
     * browser.
     */
    record Replaced(GoogleBrowser browser, String oldFlow, String newFlow) {}

    Replaced flowReplacedByAnotherTab() throws Exception {
        var browser = pendingBrowser();
        String oldFlow = browser.status().path("flowId").asText();
        FakeGoogle.subject = "second-subject";
        FakeGoogle.email = "second@gmail.com";
        pending(browser);
        String newFlow = browser.status().path("flowId").asText();
        assertThat(newFlow)
                .as("given: the second callback replaced the flow")
                .isNotEqualTo(oldFlow);
        return new Replaced(browser, oldFlow, newFlow);
    }

    boolean callbackWaitsForAccountLock() {
        return Boolean.TRUE.equals(
                jdbc.queryForObject(
                        """
                        SELECT EXISTS(
                            SELECT 1 FROM pg_stat_activity
                            WHERE datname = current_database()
                            AND wait_event_type = 'Lock' AND query LIKE '%auth_accounts%')
                        """,
                        Boolean.class));
    }

    /**
     * Accounts that are not exactly local (a password) or Google (a linked subject, no password).
     */
    long inconsistentAccounts() {
        return jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM auth_accounts a WHERE (a.password_hash IS NULL)
                <> EXISTS (SELECT 1 FROM auth_google_identities i WHERE i.account_id = a.id)
                """,
                Long.class);
    }

    Map<String, String> confirmation(Browser browser, UUID operation) {
        return Map.of("code", tokens.code(operation), "registration", browser.registration);
    }

    static List<Integer> statuses(List<HttpResponse<String>> responses) {
        return responses.stream().map(HttpResponse::statusCode).toList();
    }

    /** The error code of each response; a success has none. */
    List<String> codes(List<HttpResponse<String>> responses) {
        return responses.stream().map(this::code).toList();
    }

    static long lockKey(String name) {
        return Long.parseUnsignedLong(AuthenticationSecrets.hash(name).substring(0, 16), 16);
    }

    AuthenticationGrant signup(GoogleTransactions.Callback pending, String username) {
        return transactions.signup(
                pending.pendingId(),
                pending.pendingProof(),
                null,
                username,
                null,
                null);
    }

    <T> List<T> arrivingOneAfterAnotherAt(
            long lock, Callable<T> first, Callable<T> second) throws Exception {
        return List.copyOf(
                arrivingOneAfterAnotherAt(
                                lock,
                                List.of("first", "second"),
                                Map.of("first", first, "second", second))
                        .values());
    }

    /**
     * Holds the advisory lock that all the calls wait for, sends each call once the previous one
     * waits for it, then releases it: the lock queue serves them in arrival order. The Google calls
     * give up after the 3 s {@code lock_timeout}, hence {@link #LOCK_ARRIVAL}.
     */
    <T> Map<String, T> arrivingOneAfterAnotherAt(
            long lock, List<String> arrival, Map<String, Callable<T>> calls) throws Exception {
        var futures = new LinkedHashMap<String, Future<T>>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor();
                var held = AdvisoryLock.hold(dataSource, lock)) {
            try {
                for (String name : arrival) {
                    futures.put(name, executor.submit(calls.get(name)));
                    AdvisoryLock.awaitWaiters(jdbc, lock, futures.size(), LOCK_ARRIVAL);
                }
                held.release();
                var results = new LinkedHashMap<String, T>();
                for (var future : futures.entrySet())
                    results.put(future.getKey(), future.getValue().get(10, TimeUnit.SECONDS));
                return results;
            } finally {
                futures.values().forEach(future -> future.cancel(true));
            }
        }
    }
}
