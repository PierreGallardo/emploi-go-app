package fr.emploigo.support;

import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.SQLException;
import java.util.ArrayList;

/** Puts the shared PostgreSQL back to the state Flyway leaves it in. */
public final class TestDatabase {
    // Longer than the gates of the fake providers and the locks a test may hold.
    private static final String LOCK_TIMEOUT = "15s";
    private static final String LOCK_NOT_AVAILABLE = "55P03";

    private TestDatabase() {}

    /**
     * Empties every application table and drops a leftover {@link SqlFailure}; the lock wait is
     * bounded only here, so a lock left by a crashed test fails clearly instead of hanging.
     */
    public static void reset(JdbcTemplate jdbc) {
        jdbc.execute(
                (ConnectionCallback<Void>)
                        connection -> {
                            boolean autoCommit = connection.getAutoCommit();
                            connection.setAutoCommit(false);
                            try (var statement = connection.createStatement()) {
                                statement.execute(
                                        """
                                        SET LOCAL lock_timeout = '%s'
                                        """
                                                .formatted(LOCK_TIMEOUT));
                                statement.execute(
                                        """
                                        DROP FUNCTION IF EXISTS %s() CASCADE
                                        """
                                                .formatted(SqlFailure.FUNCTION));
                                var tables = new ArrayList<String>();
                                try (var result =
                                        statement.executeQuery(
                                                """
                                                SELECT quote_ident(table_name)
                                                FROM information_schema.tables
                                                WHERE table_schema = current_schema()
                                                AND table_type = 'BASE TABLE'
                                                AND table_name NOT IN (
                                                    'flyway_schema_history', 'scheduled_tasks')
                                                AND NOT EXISTS (
                                                    SELECT 1 FROM pg_depend
                                                    WHERE classid = 'pg_class'::regclass
                                                    AND objid = format(
                                                        '%I.%I', table_schema, table_name
                                                    )::regclass
                                                    AND deptype = 'e')
                                                """)) {
                                    while (result.next()) tables.add(result.getString(1));
                                }
                                if (!tables.isEmpty())
                                    statement.execute(
                                            """
                                            TRUNCATE TABLE %s CASCADE
                                            """
                                                    .formatted(String.join(", ", tables)));
                                connection.commit();
                            } catch (SQLException error) {
                                connection.rollback();
                                if (LOCK_NOT_AVAILABLE.equals(error.getSQLState())) {
                                    throw new IllegalStateException(
                                            "Nettoyage bloqué par un verrou PostgreSQL resté pris plus de "
                                                    + LOCK_TIMEOUT
                                                    + " : un test précédent ne l'a pas libéré.",
                                            error);
                                }
                                throw error;
                            } finally {
                                connection.setAutoCommit(autoCommit);
                            }
                            return null;
                        });
    }
}
