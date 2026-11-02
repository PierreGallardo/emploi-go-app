package fr.emploigo.support;

import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * Plans are read with {@code enable_seqscan} off, since PostgreSQL reads a small test table whole
 * whatever the indexes.
 */
public final class QueryPlans {
    private QueryPlans() {}

    /**
     * The plan of {@code statement} run with {@code arguments}, one line per node; nothing is run
     * or kept.
     */
    public static String of(JdbcTemplate jdbc, String statement, List<Object> arguments) {
        return jdbc.execute(
                (ConnectionCallback<String>)
                        connection -> {
                            boolean autoCommit = connection.getAutoCommit();
                            connection.setAutoCommit(false);
                            try (var set = connection.createStatement()) {
                                set.execute("SET LOCAL enable_seqscan = off");
                                try (var explain =
                                        connection.prepareStatement("EXPLAIN " + statement)) {
                                    for (int index = 0; index < arguments.size(); index++)
                                        explain.setObject(index + 1, arguments.get(index));
                                    var lines = new ArrayList<String>();
                                    try (var result = explain.executeQuery()) {
                                        while (result.next()) lines.add(result.getString(1));
                                    }
                                    return String.join("\n", lines);
                                }
                            } finally {
                                connection.rollback();
                                connection.setAutoCommit(autoCommit);
                            }
                        });
    }
}
