package fr.emploigo.support;

import static org.awaitility.Awaitility.await;

import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;

import javax.sql.DataSource;

/**
 * Holds a row lock from a test connection until committed, released or closed, to control the
 * application's transactions on that row.
 */
public final class RowLock implements AutoCloseable {
    private final Connection connection;

    private RowLock(Connection connection) {
        this.connection = connection;
    }

    /**
     * Locks the row in a transaction left open; {@code table} is concatenated into the SQL, so pass
     * a literal.
     */
    public static RowLock hold(DataSource dataSource, String table, Object id) throws SQLException {
        var connection = dataSource.getConnection();
        try {
            connection.setAutoCommit(false);
            try (var statement =
                    connection.prepareStatement(
                            """
                            SELECT id FROM %s WHERE id = ? FOR UPDATE
                            """
                                    .formatted(table))) {
                statement.setObject(1, id);
                statement.executeQuery().close();
            }
            return new RowLock(connection);
        } catch (SQLException | RuntimeException error) {
            connection.close();
            throw error;
        }
    }

    /**
     * Runs a write in the transaction that holds the lock; it becomes visible to others at {@link
     * #commit()}.
     */
    public void execute(String sql) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    public void commit() throws SQLException {
        connection.commit();
    }

    public void release() throws SQLException {
        connection.rollback();
    }

    @Override
    public void close() throws SQLException {
        connection.close();
    }

    /**
     * Waits until exactly {@code expected} transactions of the database wait for any lock: a
     * leftover transaction skews the count, and it does not tell which lock is awaited. {@link
     * AdvisoryLock#awaitWaiters} targets one advisory lock.
     */
    public static void awaitWaiters(JdbcTemplate jdbc, int expected, Duration atMost) {
        await().alias(expected + " transaction(s) waiting for a lock")
                .atMost(atMost)
                .pollInterval(Duration.ofMillis(20))
                .until(
                        () ->
                                jdbc.queryForObject(
                                                """
                                                SELECT COUNT(*) FROM pg_stat_activity
                                                WHERE datname = current_database()
                                                AND wait_event_type = 'Lock'
                                                """,
                                                Long.class)
                                        == expected);
    }
}
