package fr.emploigo.auth.infrastructure.quota;

import fr.emploigo.auth.application.quota.LogThrottle;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import org.slf4j.Logger;

import java.time.Clock;
import java.time.Duration;

/**
 * Reports a loss of the quota counters as a metric and a warning at most once a minute. An empty
 * Redis cannot tell a first start from a loss.
 */
public final class CounterLossReport {
    static final String LOSSES = "auth.rate_limit.counters.lost";

    /**
     * Created without a lifetime, with {@code SET NX}: when it is created, Redis is new or lost its
     * counters.
     */
    public static final String WITNESS_KEY = "auth:limits:witness";

    private final Logger log;
    private final LogThrottle warnings;
    private final Counter losses;

    CounterLossReport(Logger log, Clock clock, MeterRegistry meters) {
        this.log = log;
        this.warnings = new LogThrottle(clock, Duration.ofMinutes(1));
        // Registered at once so that it shows zero before any loss; the login and the other
        // limiters share it by name.
        this.losses =
                Counter.builder(LOSSES)
                        .description(
                                "Pertes des compteurs de quota détectées : Redis neuf ou revenu sans ses données")
                        .register(meters);
    }

    /** Never throws: noting a loss must not turn an answer of the counters into a failure. */
    void report() {
        try {
            losses.increment();
            if (warnings.allow())
                log.warn(
                        "Compteurs de quota absents : Redis est neuf ou a perdu ses données. "
                                + "Les quotas repartent de zéro ; aucune action n'est bloquée "
                                + "(métrique {} ; attendu une fois au premier démarrage).",
                        LOSSES);
        } catch (RuntimeException ignored) {
            // The metric registry or the appender failed: the loss is one line less in the logs,
            // nothing more.
        }
    }

    /**
     * What is written of a failure of Redis: the classes, never the free message, which names the
     * server.
     */
    public static String describe(RuntimeException error) {
        var cause = org.springframework.core.NestedExceptionUtils.getMostSpecificCause(error);
        return error.getClass().getSimpleName() + " : " + cause.getClass().getSimpleName();
    }
}
