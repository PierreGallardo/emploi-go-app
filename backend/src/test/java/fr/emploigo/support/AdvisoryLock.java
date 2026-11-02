package fr.emploigo.support;

import static org.awaitility.Awaitility.await;

import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;

import javax.sql.DataSource;

/**
 * Holds a transaction-level advisory lock from a test connection until released or closed, to
 * control the application's transactions that take the same lock.
 */
public final class AdvisoryLock implements AutoCloseable {
    private final Connection connection;

    private AdvisoryLock(Connection connection) {
        this.connection = connection;
    }

    public static AdvisoryLock hold(DataSource dataSource, long key) throws SQLException {
        var connection = dataSource.getConnection();
        try {
            connection.setAutoCommit(false);
            try (var statement = connection.prepareStatement("SELECT pg_advisory_xact_lock(?)")) {
                statement.setLong(1, key);
                statement.executeQuery().close();
            }
            return new AdvisoryLock(connection);
        } catch (SQLException | RuntimeException error) {
            connection.close();
            throw error;
        }
    }

    public void release() throws SQLException {
        connection.rollback();
    }

    @Override
    public void close() throws SQLException {
        connection.close();
    }

    /**
     * Waits until exactly {@code expected} transactions of the database wait for the advisory lock
     * of {@code key}; waits for other locks are not counted.
     */
    public static void awaitWaiters(JdbcTemplate jdbc, long key, int expected, Duration atMost) {
        await().alias(expected + " transaction(s) waiting for advisory lock " + key)
                .atMost(atMost)
                .pollInterval(Duration.ofMillis(20))
                .until(
                        () ->
                                jdbc.queryForObject(
                                                """
                                                SELECT COUNT(*) FROM pg_locks
                                                WHERE locktype = 'advisory' AND NOT granted
                                                AND objsubid = 1
                                                AND database = (SELECT oid FROM pg_database
                                                    WHERE datname = current_database())
                                                AND (classid::bigint << 32) | objid::bigint = ?
                                                """,
                                                Long.class,
                                                key)
                                        == expected);
    }
}
