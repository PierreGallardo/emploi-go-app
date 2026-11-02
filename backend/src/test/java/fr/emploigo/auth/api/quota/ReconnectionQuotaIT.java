package fr.emploigo.auth.api.quota;

import static fr.emploigo.support.HostCookies.RECONNECTABLE_COOKIE;
import static fr.emploigo.support.HostCookies.RECONNECTION_COOKIE;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;
import static org.mockito.Mockito.doAnswer;

import fr.emploigo.auth.application.AuthenticationIntegrationTest;
import fr.emploigo.auth.application.account.AccountRepository;
import fr.emploigo.auth.application.failure.AuthenticationFailedException;
import fr.emploigo.auth.application.session.AuthenticationSessions;
import fr.emploigo.auth.application.session.ResumeSessionUseCase;
import fr.emploigo.auth.domain.Account;
import fr.emploigo.auth.domain.AuthenticationSecrets;
import fr.emploigo.auth.infrastructure.quota.TestQuotaKeys;
import fr.emploigo.support.Browser;

import io.micrometer.core.instrument.MeterRegistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * The limit of the resumes of a reconnection: by family, counted once the secret is verified, and
 * never by address.
 */
class ReconnectionQuotaIT extends AuthenticationIntegrationTest {
    private static final int LIMIT = 10;
    private static final long WINDOW_SECONDS = 15 * 60;
    private static final Duration TOLERANCE = Duration.ofSeconds(30);

    @Autowired AccountRepository accounts;
    @Autowired AuthenticationSessions sessions;
    @Autowired ResumeSessionUseCase resumeSession;
    @Autowired MeterRegistry meters;
    private String password;

    @BeforeEach
    void fixture() {
        password = UUID.randomUUID() + " 🔑 ";
        accounts.save(
                new Account(
                        "Ecole_demo",
                        "jeune@example.invalid",
                        passwords.encode(password),
                        true,
                        true));
    }

    /** Revocations for reuse counted since the start of the application. */
    private double reuseRevocations() {
        return meters.get("auth.reconnection.reuse.revoked").counter().count();
    }

    private Browser loggedIn() throws Exception {
        var browser = newBrowser();
        var response =
                browser.post(
                        "login",
                        Map.of(
                                "identifier",
                                "jeune@example.invalid",
                                "password",
                                password,
                                "rememberMe",
                                true),
                        browser.csrf());
        assertThat(response.statusCode()).as("given: login accepted").isEqualTo(200);
        return browser;
    }

    private HttpResponse<String> resume(Browser browser) throws Exception {
        return browser.post("resume", "");
    }

    private List<UUID> families() {
        return jdbc.queryForList("SELECT id FROM auth_reconnections ORDER BY id", UUID.class);
    }

    private static String counterKey(UUID family) {
        return "auth:limits:resume:identity:" + TestQuotaKeys.KEYS.identity(family.toString());
    }

    private Browser holding(String cookie) {
        var browser = newBrowser();
        browser.cookies.put(RECONNECTION_COOKIE, cookie);
        return browser;
    }

    private List<Integer> resumesWith(String cookie, int times) {
        return IntStream.range(0, times)
                .boxed()
                .map(unchecked(_ -> resume(holding(cookie)).statusCode()))
                .toList();
    }

    @Test
    void resumesOfOneFamilyAreRefusedWith429OnceTheLimitIsReached() throws Exception {
        var browser = loggedIn();

        var statuses = browser.postStatuses("resume", "", LIMIT + 1);

        assertThat(statuses)
                .as("statuses")
                .hasSize(LIMIT + 1)
                .endsWith(429)
                .filteredOn(status -> status == 200)
                .hasSize(LIMIT);
    }

    @Test
    void refusalAnnouncesTheWaitAndChangesNothingOfTheFamilyNorOfTheCookies() throws Exception {
        var browser = loggedIn();
        browser.postStatuses("resume", "", LIMIT);
        String cookie = browser.cookies.get(RECONNECTION_COOKIE);
        String secretHash =
                jdbc.queryForObject("SELECT secret_hash FROM auth_reconnections", String.class);
        long sessionsBefore = count("spring_session");

        var response = resume(browser);

        assertThat(response.statusCode()).as("status").isEqualTo(429);
        assertThat(code(response)).as("code").isEqualTo("RATE_LIMITED");
        assertThat(response.headers().firstValueAsLong("Retry-After").orElseThrow())
                .as("retry after, in seconds")
                .isBetween(1L, WINDOW_SECONDS);
        assertThat(response.headers().allValues("set-cookie"))
                .as("cookies set by the refusal")
                .noneMatch(
                        header ->
                                header.startsWith(RECONNECTABLE_COOKIE + "=")
                                        || header.startsWith(RECONNECTION_COOKIE + "="));
        assertThat(browser.cookies.get(RECONNECTION_COOKIE))
                .as("cookie of the reconnection")
                .isEqualTo(cookie);
        assertThat(jdbc.queryForObject("SELECT secret_hash FROM auth_reconnections", String.class))
                .as("secret")
                .isEqualTo(secretHash);
        assertThat(jdbc.queryForObject("SELECT revoked FROM auth_reconnections", Boolean.class))
                .as("revoked")
                .isFalse();
        assertThat(count("spring_session")).as("sessions").isEqualTo(sessionsBefore);
    }

    @Test
    void familyResumesAgainOnceItsCounterIsOver() throws Exception {
        var browser = loggedIn();
        browser.postStatuses("resume", "", LIMIT + 1);

        redis.delete(counterKey(families().getFirst()));

        assertThat(resume(browser).statusCode()).as("status").isEqualTo(200);
    }

    @Test
    void resumesAreNotCountedByAddressSoYoungPeopleOfOneRoomResumeFreely() throws Exception {
        var browsers = List.of(loggedIn(), loggedIn(), loggedIn());

        var statuses =
                browsers.stream()
                        .flatMap(
                                unchecked(
                                        browser ->
                                                browser.postStatuses("resume", "", LIMIT).stream()))
                        .toList();

        assertThat(statuses)
                .as("statuses of three families from one address")
                .hasSize(3 * LIMIT)
                .containsOnly(200);
        assertThat(redis.keys("auth:limits:resume:ip:*")).as("counters by address").isEmpty();
    }

    /** The cookie a resume presents when the family it names must be refused. */
    interface RefusedFamily {
        String cookie(ReconnectionQuotaIT test) throws Exception;
    }

    static Stream<Arguments> refusedFamilies() {
        return Stream.of(
                argumentSet(
                        "secret faux d'une famille vivante",
                        (RefusedFamily) ReconnectionQuotaIT::wrongSecretOfALiveFamily),
                argumentSet(
                        "famille inconnue", (RefusedFamily) ReconnectionQuotaIT::unknownFamily),
                argumentSet(
                        "famille révoquée", (RefusedFamily) ReconnectionQuotaIT::revokedFamily),
                argumentSet(
                        "famille expirée", (RefusedFamily) ReconnectionQuotaIT::expiredFamily));
    }

    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("refusedFamilies")
    void resumeOfARefusedFamilyIsAnswered401AndCountsNothing(RefusedFamily family)
            throws Exception {
        var statuses = resumesWith(family.cookie(this), LIMIT + 5);

        assertThat(statuses).as("statuses").hasSize(LIMIT + 5).containsOnly(401);
        assertThat(redis.keys("auth:limits:resume:*")).as("counters").isEmpty();
    }

    private String wrongSecretOfALiveFamily() throws Exception {
        loggedIn();
        return families().getFirst() + "." + AuthenticationSecrets.generate();
    }

    private String unknownFamily() {
        return UUID.randomUUID() + "." + AuthenticationSecrets.generate();
    }

    private String revokedFamily() throws Exception {
        var owner = loggedIn();
        String cookie = owner.cookies.get(RECONNECTION_COOKIE);
        sessions.revoke(cookie, null);
        return cookie;
    }

    private String expiredFamily() throws Exception {
        var owner = loggedIn();
        String cookie = owner.cookies.get(RECONNECTION_COOKIE);
        clock.now = clock.now.plus(AuthenticationSessions.RECONNECTION_DURATION).plusSeconds(1);
        return cookie;
    }

    @Test
    void aWrongSecretDoesNotUseUpTheResumesOfTheOwner() throws Exception {
        var owner = loggedIn();
        resumesWith(families().getFirst() + "." + AuthenticationSecrets.generate(), LIMIT + 5);

        var statuses = owner.postStatuses("resume", "", LIMIT);

        assertThat(statuses).as("statuses of the owner").containsOnly(200);
    }

    @Test
    void previousSecretReplayedWithinTheToleranceIsServedAndCounted() throws Exception {
        var owner = loggedIn();
        String previous = owner.cookies.get(RECONNECTION_COOKIE);
        assertThat(resume(owner).statusCode()).as("given: resume accepted").isEqualTo(200);
        double revocationsBefore = reuseRevocations();

        var statuses = resumesWith(previous, 1);

        assertThat(statuses).as("statuses").containsOnly(200);
        assertThat(reuseRevocations()).as("revocations").isEqualTo(revocationsBefore);
        assertThat(redis.opsForValue().get(counterKey(families().getFirst())))
                .as("resumes counted")
                .isEqualTo("2");
    }

    @Test
    void previousSecretReplayedAfterTheToleranceRevokesTheFamilyAndIsNotCounted() throws Exception {
        var owner = loggedIn();
        String previous = owner.cookies.get(RECONNECTION_COOKIE);
        assertThat(resume(owner).statusCode()).as("given: resume accepted").isEqualTo(200);
        clock.now = clock.now.plus(TOLERANCE).plusSeconds(1);
        double revocationsBefore = reuseRevocations();

        var statuses = resumesWith(previous, 3);

        assertThat(statuses).as("statuses").containsOnly(401);
        assertThat(reuseRevocations() - revocationsBefore).as("revocations").isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT revoked FROM auth_reconnections", Boolean.class))
                .as("revoked")
                .isTrue();
        assertThat(redis.opsForValue().get(counterKey(families().getFirst())))
                .as("resumes counted")
                .isEqualTo("1");
    }

    /**
     * What a call to the counters saw of the thread that made it: a transaction, or any resource it
     * holds bound.
     */
    private record Observation(boolean transaction, boolean resourcesBound) {}

    @Test
    void counterIsCalledOutsideAnyTransactionAndHoldingNoConnection() throws Exception {
        var owner = loggedIn();
        var observations = new CopyOnWriteArrayList<Observation>();
        doAnswer(
                        call -> {
                            observations.add(
                                    new Observation(
                                            TransactionSynchronizationManager
                                                    .isActualTransactionActive(),
                                            !TransactionSynchronizationManager.getResourceMap()
                                                    .isEmpty()));
                            return call.callRealMethod();
                        })
                .when(limiter)
                .checkIdentity(any(), any(), any());

        var response = resume(owner);

        assertThat(response.statusCode()).as("status").isEqualTo(200);
        assertThat(observations)
                .as("calls of the counter")
                .containsExactly(new Observation(false, false));
    }

    @Test
    void familyRevokedWhileItsResumeIsBeingCountedIsRefusedWithoutRotation() throws Exception {
        var owner = loggedIn();
        String cookie = owner.cookies.get(RECONNECTION_COOKIE);
        String secretHash =
                jdbc.queryForObject("SELECT secret_hash FROM auth_reconnections", String.class);
        doAnswer(
                        call -> {
                            sessions.revoke(cookie, null);
                            return call.callRealMethod();
                        })
                .when(limiter)
                .checkIdentity(any(), any(), any());

        assertThatThrownBy(() -> resumeSession.resume(cookie))
                .as("resume")
                .isInstanceOfSatisfying(
                        AuthenticationFailedException.class,
                        refusal ->
                                assertThat(refusal.reason())
                                        .as("reason")
                                        .isEqualTo(
                                                AuthenticationFailedException.Reason.RECONNECTION_REFUSED));

        assertThat(jdbc.queryForObject("SELECT secret_hash FROM auth_reconnections", String.class))
                .as("secret")
                .isEqualTo(secretHash);
    }
}
