package fr.emploigo.auth.api;

import static fr.emploigo.support.FakeProviders.MAIL_RESPONSE;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.awaitility.Awaitility.await;

import com.zaxxer.hikari.HikariDataSource;

import fr.emploigo.auth.domain.TestPasswords;
import fr.emploigo.support.MailCounters;
import fr.emploigo.support.PoolHold;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;

import java.net.http.HttpResponse;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import javax.sql.DataSource;

/**
 * No pool connection, transaction or lock is held during the provider call: the test takes the
 * whole pool while the call is held.
 */
class MailPoolIT extends AuthMailIT {
    @Autowired DataSource dataSource;

    private int activeConnections() throws SQLException {
        return dataSource
                .unwrap(HikariDataSource.class)
                .getHikariPoolMXBean()
                .getActiveConnections();
    }

    /**
     * Fails when a connection is in use: PoolHold does not give a pool that it could not take
     * whole.
     */
    private void takeTheWholePool() throws SQLException {
        PoolHold.ofAll(dataSource).close();
    }

    private long advisoryLocks() {
        return jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM pg_locks WHERE locktype = 'advisory'
                AND database = (SELECT oid FROM pg_database WHERE datname = current_database())
                """,
                Long.class);
    }

    private long transactionsLeftOpen() {
        return jdbc.queryForObject(
                """
                    SELECT COUNT(*) FROM pg_stat_activity
                    WHERE datname = current_database()
                    AND state LIKE 'idle in transaction%'
                    AND pid <> pg_backend_pid()
                    """,
                Long.class);
    }

    /**
     * A recovery request: it computes no password hash, so no limit other than the transport meets
     * the requests.
     */
    private HttpResponse<String> recoveryOf(int index) throws Exception {
        return newBrowser()
                .post("password/forgot", Map.of("email", "jeune" + index + "@example.invalid"));
    }

    private void ownersOfTheRecoveryRequests(int requests) {
        jdbc.update(
                """
                INSERT INTO auth_accounts(
                    username, email_key, password_hash, email_verified, enabled)
                SELECT 'jeune' || n, 'jeune' || n || '@example.invalid', ?, TRUE, TRUE
                FROM generate_series(0, ? - 1) AS n
                """,
                passwords.encode(TestPasswords.random()),
                requests);
    }

    private long pendingResets() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM auth_password_resets WHERE status = 'PENDING'", Long.class);
    }

    /**
     * The response is not what this test looks at: it waits for it, so that no request outlives the
     * test.
     */
    private static void awaitResponse(Future<HttpResponse<String>> future) {
        try {
            future.get();
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(SendingFlow.class)
    void theWholePoolIsFreeWhileTheProviderCallIsInProgress(SendingFlow flow) throws Exception {
        flow.given(this);
        var mail = new MailCounters();
        HttpResponse<String> response;

        try (var pool = Executors.newVirtualThreadPerTaskExecutor();
                var hold = MAIL_RESPONSE.hold()) {
            var request = pool.submit(() -> flow.request(this));
            hold.awaitRequests(1);

            assertThatCode(this::takeTheWholePool)
                    .as("the whole pool taken during the call")
                    .doesNotThrowAnyException();
            assertThat(activeConnections()).as("connections in use during the call").isZero();
            hold.release();
            response = request.get();
        }

        assertThat(response.statusCode()).as("status").isEqualTo(202);
        assertThat(mail.sends()).as("messages sent").isEqualTo(1);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(SendingFlow.class)
    void noLockAndNoTransactionAreHeldWhileTheProviderCallIsInProgress(SendingFlow flow)
            throws Exception {
        flow.given(this);
        long locks;
        long open;

        try (var pool = Executors.newVirtualThreadPerTaskExecutor();
                var hold = MAIL_RESPONSE.hold()) {
            var request = pool.submit(() -> flow.request(this));
            hold.awaitRequests(1);

            locks = advisoryLocks();
            open = transactionsLeftOpen();
            hold.release();
            request.get();
        }

        assertThat(locks).as("advisory locks held during the call").isZero();
        assertThat(open).as("transactions left open during the call").isZero();
    }

    @Test
    void slowSendsOfSeveralAddressesHoldNoConnectionWhileTheProviderAnswers() throws Exception {
        int requests = 4;
        ownersOfTheRecoveryRequests(requests);
        int atTheProvider;
        int active;

        try (var pool = Executors.newVirtualThreadPerTaskExecutor();
                var hold = MAIL_RESPONSE.hold()) {
            var futures =
                    IntStream.range(0, requests)
                            .<Callable<HttpResponse<String>>>mapToObj(
                                    index -> () -> recoveryOf(index))
                            .map(pool::submit)
                            .toList();
            hold.awaitRequests(requests);
            // Every request has kept its reset: none has anything left to do in the database
            // until its result.
            await().atMost(Duration.ofSeconds(1)).until(() -> pendingResets() == requests);

            atTheProvider = hold.requests();
            assertThatCode(this::takeTheWholePool)
                    .as("the whole pool taken during the sends")
                    .doesNotThrowAnyException();
            active = activeConnections();
            hold.release();
            futures.forEach(future -> awaitResponse(future));
        }

        assertThat(atTheProvider).as("calls at the provider").isEqualTo(requests);
        assertThat(active).as("connections in use by the sends in progress").isZero();
    }
}
