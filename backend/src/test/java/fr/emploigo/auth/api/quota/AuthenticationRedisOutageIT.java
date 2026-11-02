package fr.emploigo.auth.api.quota;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zaxxer.hikari.HikariDataSource;

import fr.emploigo.auth.application.AuthenticationIntegrationTest;
import fr.emploigo.auth.application.AuthenticationTestSupport;
import fr.emploigo.auth.application.account.AccountRepository;
import fr.emploigo.auth.domain.Account;
import fr.emploigo.auth.domain.TestPasswords;
import fr.emploigo.support.Browser;
import fr.emploigo.support.Containers;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.test.web.servlet.MockMvc;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import javax.sql.DataSource;

/**
 * A Redis that does not answer or restarts: refusals come within the command timeout, the database
 * pool is not drained, and authentication comes back without waiting. The actions of each scope and
 * a full memory are in {@link QuotaFailureModeIT}.
 */
class AuthenticationRedisOutageIT extends AuthenticationIntegrationTest {
    private static final String EMAIL = "jeune@example.invalid";

    /** Logins sent while Redis is paused, each refused after the command timeout. */
    private static final int REFUSALS = 5;

    private static final int MORE_THAN_THE_POOL = 25;

    @Autowired AccountRepository accounts;
    @Autowired DataSource dataSource;
    @Autowired ApplicationContext context;
    @Autowired MockMvc mvc;
    private String password;

    @BeforeEach
    void fixture() {
        password = UUID.randomUUID() + " 🔑 ";
        accounts.save(
                new Account("Ecole_demo", EMAIL, passwords.encode(password), true, true));
    }

    private Map<String, String> credentials() {
        return Map.of("identifier", EMAIL, "password", password);
    }

    private HttpResponse<String> login(Browser browser, String csrf) throws Exception {
        return browser.post("login", credentials(), csrf);
    }

    private Browser loggedIn() throws Exception {
        var browser = newBrowser();
        assertThat(login(browser, browser.csrf()).statusCode())
                .as("given: login accepted")
                .isEqualTo(200);
        return browser;
    }

    @Test
    void firstLoginRefusedWhileRedisIsPausedAnswers503WithinEightHundredMilliseconds()
            throws Exception {
        loggedIn();
        var anonymous = newBrowser();
        String csrf = anonymous.csrf();

        try (var _ = Containers.pauseRedis()) {
            long start = System.nanoTime();
            var response = login(anonymous, csrf);
            var elapsed = Duration.ofNanos(System.nanoTime() - start);

            assertThat(response.statusCode()).as("status").isEqualTo(503);
            assertThat(elapsed).as("time of the first refusal").isLessThan(Duration.ofMillis(800));
        }
    }

    @Test
    void loginsKeepBeingRefusedWhileRedisIsPausedWithinTheCommandTimeout() throws Exception {
        loggedIn();
        var anonymous = newBrowser();
        String csrf = anonymous.csrf();

        try (var _ = Containers.pauseRedis()) {
            assertThat(anonymous.postStatuses("login", credentials(), REFUSALS))
                    .as("statuses of the logins before the last")
                    .containsOnly(503);
            long start = System.nanoTime();
            var response = login(anonymous, csrf);
            var elapsed = Duration.ofNanos(System.nanoTime() - start);

            assertThat(response.statusCode()).as("status").isEqualTo(503);
            assertThat(response.headers().firstValue("Retry-After"))
                    .as("Retry-After")
                    .contains("5");
            assertThat(elapsed).as("time of a refusal").isLessThan(Duration.ofMillis(800));
        }
    }

    @Test
    void sessionAndEstablishmentsOfALoggedInYouthAnswer200WhileLoginsWaitForARedisThatIsPaused()
            throws Exception {
        var youth = loggedIn();
        var authentication = AuthenticationTestSupport.of(context);
        var cookie = authentication.cookie(authentication.newAccount("Carte"), "JEUNE");
        var anonymous = IntStream.range(0, MORE_THAN_THE_POOL).mapToObj(_ -> newBrowser()).toList();
        var tokens = anonymous.stream().map(unchecked(Browser::csrf)).toList();

        try (var _ = Containers.pauseRedis();
                var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            var logins =
                    IntStream.range(0, MORE_THAN_THE_POOL)
                            .mapToObj(
                                    i ->
                                            threads.submit(
                                                    () ->
                                                            login(anonymous.get(i), tokens.get(i))
                                                                    .statusCode()))
                            .toList();

            long start = System.nanoTime();
            var session = youth.get("session");
            mvc.perform(get("/api/establishments").cookie(cookie)).andExpect(status().isOk());
            var elapsed = Duration.ofNanos(System.nanoTime() - start);

            assertThat(session.statusCode()).as("status of the session").isEqualTo(200);
            // With a pool of 10 connections held by the logins, a request would wait the 5 s of the
            // pool for one.
            assertThat(elapsed).as("time of both reads").isLessThan(Duration.ofSeconds(2));
            assertThat(logins.stream().map(unchecked(Future::get)).toList())
                    .as("statuses of the logins")
                    .hasSize(MORE_THAN_THE_POOL)
                    .containsOnly(503);
        }
    }

    @Test
    void noConnectionOfThePoolIsHeldWhileTheCountersWaitForRedis() throws Exception {
        // The reset of a password asks the quota before it reads anything: a connection seen in
        // use can only be held by the wait.
        var anonymous = IntStream.range(0, MORE_THAN_THE_POOL).mapToObj(_ -> newBrowser()).toList();
        var tokens = anonymous.stream().map(unchecked(Browser::csrf)).toList();
        var pool = dataSource.unwrap(HikariDataSource.class).getHikariPoolMXBean();
        var peak = new AtomicInteger();
        String password = TestPasswords.random();

        try (var _ = Containers.pauseRedis();
                var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            var resets =
                    IntStream.range(0, MORE_THAN_THE_POOL)
                            .mapToObj(
                                    i ->
                                            threads.submit(
                                                    () ->
                                                            anonymous
                                                                    .get(i)
                                                                    .post(
                                                                            "password/reset",
                                                                            Map.of(
                                                                                    "token",
                                                                                    UUID.randomUUID()
                                                                                            + "."
                                                                                            + "s"
                                                                                                    .repeat(
                                                                                                            43),
                                                                                    "password",
                                                                                    password,
                                                                                    "confirmation",
                                                                                    password),
                                                                            tokens.get(i))
                                                                    .statusCode()))
                            .toList();

            await().atMost(Duration.ofSeconds(10))
                    .pollInterval(Duration.ofMillis(2))
                    .until(
                            () -> {
                                peak.accumulateAndGet(pool.getActiveConnections(), Math::max);
                                return resets.stream().allMatch(Future::isDone);
                            });

            assertThat(resets.stream().map(unchecked(Future::get)).toList())
                    .as("statuses")
                    .containsOnly(503);
        }
        assertThat(peak.get()).as("peak of connections in use during the wait").isZero();
    }

    @Test
    void loginComesBackWithinTenSecondsOnceRedisAnswersAgain() throws Exception {
        var browser = newBrowser();
        try (var _ = Containers.pauseRedis()) {
            // Another identifier each time: a paused Redis executes its queued commands on resume,
            // so repeats would count as failures.
            var statuses =
                    IntStream.range(0, REFUSALS)
                            .boxed()
                            .map(
                                    unchecked(
                                            i ->
                                                    browser.post(
                                                                    "login",
                                                                    Map.of(
                                                                            "identifier",
                                                                            "absent-" + i,
                                                                            "password",
                                                                            password),
                                                                    browser.csrf())
                                                            .statusCode()))
                            .toList();
            assertThat(statuses).as("given: logins refused").containsOnly(503);
        }

        long start = System.nanoTime();
        var response = login(browser, browser.csrf());
        var elapsed = Duration.ofNanos(System.nanoTime() - start);

        assertThat(response.statusCode()).as("status").isEqualTo(200);
        assertThat(elapsed).as("time to come back").isLessThan(Duration.ofSeconds(10));
    }

    @Test
    void loginComesBackWithinTenSecondsAfterRedisRestartsWithANewRunId() throws Exception {
        var browser = newBrowser();
        assertThat(login(browser, browser.csrf()).statusCode())
                .as("given: login accepted")
                .isEqualTo(200);
        String before = Containers.redisRunId();

        long start = System.nanoTime();
        Containers.restartRedis();
        await().atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(100))
                .ignoreExceptions()
                .until(() -> login(browser, browser.csrf()).statusCode() == 200);
        var elapsed = Duration.ofNanos(System.nanoTime() - start);

        assertThat(Containers.redisRunId()).as("run id after the restart").isNotEqualTo(before);
        assertThat(elapsed).as("time to come back").isLessThan(Duration.ofSeconds(10));
    }
}
