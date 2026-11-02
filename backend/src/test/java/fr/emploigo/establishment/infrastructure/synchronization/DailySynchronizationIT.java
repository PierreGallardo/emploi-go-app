package fr.emploigo.establishment.infrastructure.synchronization;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.kagkarlsson.scheduler.task.helper.RecurringTask;
import com.github.kagkarlsson.scheduler.testhelper.ManualScheduler;
import com.github.kagkarlsson.scheduler.testhelper.SettableClock;
import com.github.kagkarlsson.scheduler.testhelper.TestHelper;

import fr.emploigo.support.WorkerTest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.LocalTime;
import java.util.List;

import javax.sql.DataSource;

/**
 * The retries of every daily task of the establishments, on a scheduler of its own: its own table
 * and its own clock, so the scheduler of the worker never sees them.
 */
@WorkerTest
class DailySynchronizationIT {
    private static final String TABLE = "daily_synchronization_test_tasks";
    // 02:00 in Paris, a day without a change of the hour.
    private static final Instant START = Instant.parse("2026-11-10T01:00:00Z");

    @Autowired DataSource data;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void aTableOfItsOwn() {
        jdbc.execute("CREATE TABLE %s (LIKE scheduled_tasks INCLUDING ALL)".formatted(TABLE));
    }

    @AfterEach
    void dropTheTable() {
        jdbc.execute("DROP TABLE IF EXISTS " + TABLE);
    }

    @ParameterizedTest
    @CsvSource({
        "synchronize-establishments, 11:00, 2026-11-10T10:00:00Z",
        "synchronize-transit-stops, 04:00, 2026-11-11T03:00:00Z",
        "synchronize-addresses, 04:10, 2026-11-11T03:10:00Z",
        "purge-excluded-establishments, 05:30, 2026-11-10T04:30:00Z"
    })
    void failedRunIsTriedAgainThreeTimesAnHourApartThenAtTheNextDailyHour(
            String name, LocalTime dailyAt, Instant nextDaily) {
        RecurringTask<Void> task =
                DailySynchronization.task(
                        name,
                        dailyAt,
                        () -> {
                            throw new IllegalStateException("panne fictive");
                        });
        var clock = new SettableClock();
        clock.set(START);
        ManualScheduler scheduler =
                TestHelper.createManualScheduler(data, List.of(task))
                        .tableName(TABLE)
                        .clock(clock)
                        .build();
        scheduler.schedule(task.instance(RecurringTask.INSTANCE), START);

        scheduler.runAnyDueExecutions();
        Execution firstRetry = execution(name);
        scheduler.tick(DailySynchronization.RETRY_DELAY);
        scheduler.runAnyDueExecutions();
        scheduler.tick(DailySynchronization.RETRY_DELAY);
        scheduler.runAnyDueExecutions();
        scheduler.tick(DailySynchronization.RETRY_DELAY);
        scheduler.runAnyDueExecutions();

        assertThat(firstRetry)
                .isEqualTo(new Execution(START.plus(DailySynchronization.RETRY_DELAY), false, 1));
        assertThat(execution(name)).isEqualTo(new Execution(nextDaily, false, 0));
    }

    private record Execution(Instant at, boolean picked, int failures) {}

    private Execution execution(String name) {
        return jdbc.queryForObject(
                """
                SELECT execution_time, picked, consecutive_failures
                FROM %s
                WHERE task_name = ?
                """
                        .formatted(TABLE),
                (row, number) ->
                        new Execution(
                                row.getTimestamp(1).toInstant(), row.getBoolean(2), row.getInt(3)),
                name);
    }
}
