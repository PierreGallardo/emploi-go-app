package fr.emploigo.support;

import com.github.kagkarlsson.scheduler.task.helper.OneTimeTask;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Shared task payload: the API stores {@link #PAYLOAD} as {@link #PAYLOAD_JSON}, and the worker
 * must read it back identically.
 */
public final class SchedulerFixtures {
    public static final String TASK = "test-round-trip";

    public record Payload(String label, int count, Instant at, List<String> tags, String note) {}

    public static final Payload PAYLOAD =
            new Payload(
                    "Rappel de l'événement « Forum des métiers »",
                    3,
                    Instant.parse("2026-10-20T08:30:00Z"),
                    List.of("jeune", "été"),
                    null);

    public static final String PAYLOAD_JSON =
            "{\"label\":\"Rappel de l'événement « Forum des métiers »\",\"count\":3,"
                    + "\"at\":\"2026-10-20T08:30:00Z\",\"tags\":[\"jeune\",\"été\"],\"note\":null}";

    public static final BlockingQueue<Payload> RECEIVED = new LinkedBlockingQueue<>();

    private SchedulerFixtures() {}

    @TestConfiguration(proxyBeanMethods = false)
    public static class RoundTripTask {
        @Bean
        OneTimeTask<Payload> roundTripTask() {
            return Tasks.oneTime(TASK, Payload.class)
                    .execute((instance, context) -> RECEIVED.add(instance.getData()));
        }
    }
}
