package fr.emploigo.establishment.infrastructure.synchronization;

import com.github.kagkarlsson.scheduler.Clock;
import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.exceptions.TaskInstanceException;
import com.github.kagkarlsson.scheduler.task.ExecutionContext;
import com.github.kagkarlsson.scheduler.task.FailureHandler;
import com.github.kagkarlsson.scheduler.task.SchedulableInstance;
import com.github.kagkarlsson.scheduler.task.TaskInstance;
import com.github.kagkarlsson.scheduler.task.helper.RecurringTask;
import com.github.kagkarlsson.scheduler.task.schedule.Schedule;
import com.github.kagkarlsson.scheduler.task.schedule.Schedules;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Optional;
import java.util.function.BooleanSupplier;

/**
 * A daily task of the worker, run by one worker at a time. A failed run keeps what it wrote and
 * starts over an hour later, three times at most, then at the next daily hour. An import that never
 * succeeded in the database runs as soon as a worker starts, instead of at its daily hour.
 */
final class DailySynchronization extends RecurringTask<Void> {
    static final int RETRIES = 3;
    static final Duration RETRY_DELAY = Duration.ofHours(1);
    private static final Logger LOG = LoggerFactory.getLogger(DailySynchronization.class);

    private final Schedule daily;
    private final BooleanSupplier firstRunDue;
    private final Runnable synchronization;
    private final Optional<DailySynchronization> next;

    private DailySynchronization(
            String name,
            Schedule daily,
            BooleanSupplier firstRunDue,
            Runnable synchronization,
            Optional<DailySynchronization> next) {
        super(name, daily, Void.class, retries(daily));
        this.daily = daily;
        this.firstRunDue = firstRunDue;
        this.synchronization = synchronization;
        this.next = next;
    }

    /** A task that imports nothing: it always waits for its daily hour. */
    static DailySynchronization task(String name, LocalTime dailyAt, Runnable synchronization) {
        return importing(name, dailyAt, () -> false, synchronization);
    }

    /**
     * An import, run at startup while {@code firstRunDue} holds: it never succeeded, and what it
     * reads is in place.
     */
    static DailySynchronization importing(
            String name, LocalTime dailyAt, BooleanSupplier firstRunDue, Runnable synchronization) {
        return new DailySynchronization(
                name,
                Schedules.daily(ZoneId.of("Europe/Paris"), dailyAt),
                firstRunDue,
                synchronization,
                Optional.empty());
    }

    /** The same task, which starts {@code next} once its own first import has run. */
    DailySynchronization then(DailySynchronization next) {
        return new DailySynchronization(
                getName(), daily, firstRunDue, synchronization, Optional.of(next));
    }

    private static FailureHandler<Void> retries(Schedule daily) {
        return (complete, operations) -> {
            // The failures before this one; a new execution starts the next day with its retries
            // again.
            if (complete.getExecution().consecutiveFailures < RETRIES)
                operations.reschedule(complete, complete.getTimeDone().plus(RETRY_DELAY));
            else
                operations.removeAndScheduleNew(
                        SchedulableInstance.of(
                                complete.getExecution().taskInstance,
                                daily.getNextExecutionTime(complete)));
        };
    }

    @Override
    public void onStartup(SchedulerClient scheduler, Clock clock) {
        // A retry differs from the daily hour: the schedule on startup would move it there and
        // clear its failures.
        boolean retrying =
                scheduler
                        .getScheduledExecution(getDefaultTaskInstance())
                        .filter(execution -> execution.getConsecutiveFailures() > 0)
                        .isPresent();
        if (retrying) return;
        super.onStartup(scheduler, clock);
        startIfFirstRunDue(scheduler, clock.now(), "au démarrage du worker");
    }

    @Override
    public void executeRecurringly(TaskInstance<Void> instance, ExecutionContext context) {
        boolean first = next.isPresent() && firstRunDue.getAsBoolean();
        synchronization.run();
        // The instant this run was due is past: the next task is due at once.
        if (first)
            next.get()
                    .startIfFirstRunDue(
                            context.getSchedulerClient(),
                            context.getExecution().executionTime,
                            "après le premier import de " + getName());
    }

    /**
     * Brings the execution forward; never fails, so that a successful import stays one. The update
     * checks the version of the execution, so a worker never brings forward one that another
     * worker picked meanwhile.
     */
    private void startIfFirstRunDue(SchedulerClient scheduler, Instant at, String cause) {
        try {
            if (!firstRunDue.getAsBoolean()) return;
            scheduler.reschedule(getDefaultTaskInstance(), at);
            LOG.info("Premier import de {} : lancé {}", getName(), cause);
        } catch (TaskInstanceException pickedOrMoved) {
            LOG.info(
                    "Premier import de {} : déjà pris ou avancé par un autre worker", getName());
        } catch (RuntimeException error) {
            LOG.warn(
                    "Premier import de {} : non lancé {}, il attend l'heure quotidienne",
                    getName(),
                    cause,
                    error);
        }
    }
}
