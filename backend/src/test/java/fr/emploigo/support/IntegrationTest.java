package fr.emploigo.support;

import static org.awaitility.Awaitility.await;

import fr.emploigo.auth.application.AuthenticationTestDoubles;
import fr.emploigo.auth.application.AuthenticationTestSupport;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import tools.jackson.databind.ObjectMapper;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Base of the API integration tests (PostgreSQL, Redis, fake providers): subclasses share the
 * containers and one Spring context per profile, so none of them may use {@code @DirtiesContext}.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.jpa.properties.hibernate.generate_statistics=true",
            // Not the default of 30 minutes, so a session that ignores SESSION_TIMEOUT shows; set
            // here because the
            // properties of IntegrationConfiguration are registered after the session settings are
            // read.
            "spring.session.timeout=31m",
            // The session cleanup borrows a pool connection that would disturb the tests counting
            // them, so "-"
            // disables it (Scheduled.CRON_DISABLED): no test needs it, an expired session is
            // refused when read.
            "spring.session.jdbc.cleanup-cron=-",
            "emploi-go.establishment-images.directory=${java.io.tmpdir}/emploi-go-establishment-images"
        })
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(IntegrationConfiguration.class)
@AuthenticationTestDoubles
public abstract class IntegrationTest {
    private static final Duration OVERLAP_ARRIVAL = Duration.ofSeconds(1);

    /**
     * How long the preparation waits for a Redis that does not answer, as it waits for a lock of
     * PostgreSQL ({@link TestDatabase}).
     */
    private static final Duration REDIS_ANSWER_DELAY = Duration.ofSeconds(15);

    @LocalServerPort protected int port;

    /** Inactivity timeout of the sessions of this context ({@code SESSION_TIMEOUT}). */
    @Value("${spring.session.timeout}")
    protected Duration sessionTimeout;

    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected StringRedisTemplate redis;
    @Autowired protected ObjectMapper json;
    @Autowired protected TestClock clock;
    @Autowired private ApplicationContext context;

    @BeforeEach
    protected void resetEnvironment() {
        Containers.ensureRedisRunning();
        Containers.ensureRedisMemoryFree();
        AuthenticationTestSupport.resetBetweenTests(context, clock);
        FakeProviders.reset();
        clock.reset();
        FakeGoogle.clock = clock;
        TestDatabase.reset(jdbc);
        emptyRedisLeavingTheWitness();
    }

    /**
     * Redis is emptied but for the witness of the counters, so the first call does not report a
     * loss that no test provoked; the two commands are idempotent, so a stalled Redis is retried
     * for {@link #REDIS_ANSWER_DELAY} at most.
     */
    private void emptyRedisLeavingTheWitness() {
        await().atMost(REDIS_ANSWER_DELAY)
                .pollInterval(Duration.ofMillis(50))
                .ignoreExceptionsInstanceOf(DataAccessException.class)
                .untilAsserted(
                        () -> {
                            redis.execute(
                                    (RedisCallback<Void>)
                                            connection -> {
                                                connection.serverCommands().flushDb();
                                                return null;
                                            });
                            AuthenticationTestSupport.reserveWitness(redis);
                        });
    }

    @AfterEach
    void resumeRedis() {
        Containers.ensureRedisRunning();
        Containers.ensureRedisMemoryFree();
    }

    protected Browser newBrowser() {
        return new Browser(port, json);
    }

    protected long count(String table) {
        return jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM %s
                """
                        .formatted(table),
                Long.class);
    }

    protected String code(HttpResponse<String> response) {
        return json.readTree(response.body()).path("code").asString();
    }

    protected boolean flag(HttpResponse<String> response, String field) {
        return json.readTree(response.body()).path(field).asBoolean();
    }

    /**
     * A step of a stream that may throw a checked exception, as the HTTP calls of {@link Browser}
     * do.
     */
    @FunctionalInterface
    protected interface Step<T, R> {
        R apply(T input) throws Exception;
    }

    /**
     * Lets a stream run a {@link Step}: its failure stops the stream and fails the test, with the
     * failure as cause.
     */
    protected static <T, R> Function<T, R> unchecked(Step<T, R> step) {
        return input -> {
            try {
                return step.apply(input);
            } catch (RuntimeException failure) {
                throw failure;
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(failure);
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        };
    }

    /**
     * Runs {@code times} calls once a single gate opens, so they start together, and returns their
     * results in order.
     */
    protected static <T> List<T> inParallel(int times, Step<Integer, T> call) throws Exception {
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var gate = new CountDownLatch(1);
            var futures =
                    java.util.stream.IntStream.range(0, times)
                            .mapToObj(
                                    index ->
                                            pool.submit(
                                                    () -> {
                                                        gate.await();
                                                        return call.apply(index);
                                                    }))
                            .toList();
            gate.countDown();
            try {
                return futures.stream()
                        .map(unchecked(future -> future.get(30, TimeUnit.SECONDS)))
                        .toList();
            } finally {
                // Closing the pool waits for its tasks: cancel them so a blocked call fails the
                // test.
                futures.forEach(future -> future.cancel(true));
            }
        }
    }

    /**
     * Holds the row until both calls wait for a lock, so they overlap whichever starts first, and
     * returns their results in order; the Google and recovery transactions give up after 3 s of
     * {@code lock_timeout}, hence the 1 s of {@link #OVERLAP_ARRIVAL}.
     */
    protected <T> List<T> overlappingAtTheRow(
            String table, Object id, Callable<T> first, Callable<T> second) throws Exception {
        try (var pool = Executors.newVirtualThreadPerTaskExecutor();
                var lock = RowLock.hold(jdbc.getDataSource(), table, id)) {
            var one = pool.submit(first);
            var two = pool.submit(second);
            try {
                RowLock.awaitWaiters(jdbc, 2, OVERLAP_ARRIVAL);
                lock.release();
                return List.of(one.get(10, TimeUnit.SECONDS), two.get(10, TimeUnit.SECONDS));
            } finally {
                // Closing the pool waits for its tasks: cancel them so a blocked call fails the
                // test.
                one.cancel(true);
                two.cancel(true);
            }
        }
    }
}
