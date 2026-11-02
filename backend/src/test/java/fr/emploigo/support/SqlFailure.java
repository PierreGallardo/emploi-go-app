package fr.emploigo.support;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Makes statements fail in PostgreSQL; use it in try-with-resources, since closing drops the
 * trigger even when the test fails.
 */
public final class SqlFailure implements AutoCloseable {
    static final String FUNCTION = "auth_test_fail";
    private final JdbcTemplate jdbc;
    private final String table;

    private SqlFailure(JdbcTemplate jdbc, String table) {
        this.jdbc = jdbc;
        this.table = table;
    }

    /**
     * @param event for example {@code "BEFORE INSERT"} or {@code "BEFORE UPDATE OF state"}
     */
    public static SqlFailure inject(JdbcTemplate jdbc, String table, String event) {
        return create(jdbc, table, event, "");
    }

    /**
     * @param condition SQL condition on {@code NEW}, for example {@code "NEW.state = 'ACCEPTED'"}
     */
    public static SqlFailure inject(
            JdbcTemplate jdbc, String table, String event, String condition) {
        return create(jdbc, table, event, " WHEN (" + condition + ")");
    }

    private static SqlFailure create(JdbcTemplate jdbc, String table, String event, String when) {
        jdbc.execute(
                "CREATE FUNCTION "
                        + FUNCTION
                        + "() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN RAISE EXCEPTION ''fixture unavailable''; END'");
        var failure = new SqlFailure(jdbc, table);
        try {
            jdbc.execute(
                    "CREATE TRIGGER "
                            + FUNCTION
                            + " "
                            + event
                            + " ON "
                            + table
                            + " FOR EACH ROW"
                            + when
                            + " EXECUTE FUNCTION "
                            + FUNCTION
                            + "()");
        } catch (RuntimeException error) {
            failure.close();
            throw error;
        }
        return failure;
    }

    @Override
    public void close() {
        jdbc.execute("DROP TRIGGER IF EXISTS " + FUNCTION + " ON " + table);
        jdbc.execute("DROP FUNCTION IF EXISTS " + FUNCTION + "()");
    }
}
