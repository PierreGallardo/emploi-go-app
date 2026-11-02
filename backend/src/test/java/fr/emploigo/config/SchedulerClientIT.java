package fr.emploigo.config;

import static fr.emploigo.support.SchedulerFixtures.PAYLOAD;
import static fr.emploigo.support.SchedulerFixtures.PAYLOAD_JSON;
import static fr.emploigo.support.SchedulerFixtures.TASK;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.serializer.Serializer;
import com.github.kagkarlsson.scheduler.task.TaskInstance;

import fr.emploigo.support.IntegrationTest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

import javax.sql.DataSource;

/**
 * The API's scheduling client: a task follows the commit or the rollback of its transaction. The
 * worker side is in {@code WorkerInfrastructureIT}.
 */
class SchedulerClientIT extends IntegrationTest {
    private static final String INSTANCE = "from-the-api";

    @Autowired SchedulerClient scheduler;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired DataSource dataSource;
    @Autowired Serializer serializer;
    private TransactionTemplate transaction;

    @BeforeEach
    void aCleanTaskTable() {
        transaction = new TransactionTemplate(transactionManager);
        deleteTheTasks();
    }

    @AfterEach
    void deleteTheTasks() {
        jdbc.update("DELETE FROM scheduled_tasks WHERE task_name = ?", TASK);
    }

    /**
     * Due a day from now: the scheduler of the worker, which other tests keep alive, never picks
     * it.
     */
    private Instant due() {
        return clock.instant().plus(Duration.ofDays(1));
    }

    private long scheduled() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM scheduled_tasks WHERE task_name = ?", Long.class, TASK);
    }

    @Test
    void aTaskScheduledInACommittedTransactionExists() {
        transaction.executeWithoutResult(
                status -> scheduler.schedule(new TaskInstance<>(TASK, INSTANCE, PAYLOAD), due()));

        assertThat(scheduled()).as("scheduled tasks").isEqualTo(1);
    }

    @Test
    void aTaskScheduledInARolledBackTransactionDoesNotExist() {
        transaction.executeWithoutResult(
                status -> {
                    scheduler.schedule(new TaskInstance<>(TASK, INSTANCE, PAYLOAD), due());
                    status.setRollbackOnly();
                });

        assertThat(scheduled()).as("scheduled tasks").isZero();
    }

    /**
     * A client on the data source itself, which does not follow the transaction, keeps the task.
     */
    @Test
    void aClientOnADataSourceThatDoesNotFollowTheTransactionKeepsTheTaskOfARolledBackTransaction() {
        var plain = SchedulerClient.Builder.create(dataSource).serializer(serializer).build();

        transaction.executeWithoutResult(
                status -> {
                    plain.schedule(new TaskInstance<>(TASK, INSTANCE, PAYLOAD), due());
                    status.setRollbackOnly();
                });

        assertThat(scheduled()).as("scheduled tasks").isEqualTo(1);
    }

    @Test
    void theDataOfATaskIsStoredAsJson() {
        transaction.executeWithoutResult(
                status -> scheduler.schedule(new TaskInstance<>(TASK, INSTANCE, PAYLOAD), due()));

        byte[] stored =
                jdbc.queryForObject(
                        "SELECT task_data FROM scheduled_tasks WHERE task_name = ?",
                        byte[].class,
                        TASK);

        assertThat(new String(stored, StandardCharsets.UTF_8))
                .as("stored data")
                .isEqualTo(PAYLOAD_JSON);
    }
}
