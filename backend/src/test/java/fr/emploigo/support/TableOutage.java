package fr.emploigo.support;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Makes every statement on a table fail, reads included, by renaming it; use it in
 * try-with-resources, since closing gives the table its name back even when the test fails.
 */
public final class TableOutage implements AutoCloseable {
    private static final String SUFFIX = "_outage";

    private final JdbcTemplate jdbc;
    private final String table;

    private TableOutage(JdbcTemplate jdbc, String table) {
        this.jdbc = jdbc;
        this.table = table;
    }

    public static TableOutage of(JdbcTemplate jdbc, String table) {
        jdbc.execute("ALTER TABLE " + table + " RENAME TO " + table + SUFFIX);
        return new TableOutage(jdbc, table);
    }

    @Override
    public void close() {
        jdbc.execute("ALTER TABLE " + table + SUFFIX + " RENAME TO " + table);
    }
}
