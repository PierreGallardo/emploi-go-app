package fr.emploigo.support;

import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import javax.sql.DataSource;

/**
 * Holds every connection of the pool until closed: a request that needs the database waits out the
 * pool timeout and fails with a 503, while one answered meanwhile took no connection.
 */
public final class PoolHold implements AutoCloseable {
    private final List<Connection> held;

    private PoolHold(List<Connection> held) {
        this.held = held;
    }

    /**
     * Takes all the connections of the pool; a test that cannot take them all fails, instead of
     * proving nothing.
     */
    public static PoolHold ofAll(DataSource dataSource) throws SQLException {
        int size = dataSource.unwrap(HikariDataSource.class).getMaximumPoolSize();
        var held = new ArrayList<Connection>();
        try {
            IntStream.range(0, size).forEach(_ -> held.add(connection(dataSource)));
        } catch (RuntimeException error) {
            release(held);
            throw error;
        }
        return new PoolHold(held);
    }

    private static Connection connection(DataSource dataSource) {
        try {
            return dataSource.getConnection();
        } catch (SQLException error) {
            throw new IllegalStateException(
                    "The pool could not be taken whole: another connection is in use", error);
        }
    }

    private static void release(List<Connection> connections) {
        connections.forEach(
                connection -> {
                    try {
                        connection.close();
                    } catch (SQLException ignored) {
                        // The connection goes back to the pool or is dropped: nothing more to do in
                        // a cleanup.
                    }
                });
    }

    @Override
    public void close() {
        release(held);
    }
}
