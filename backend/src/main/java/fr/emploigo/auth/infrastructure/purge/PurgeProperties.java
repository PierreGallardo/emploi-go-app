package fr.emploigo.auth.infrastructure.purge;

import fr.emploigo.config.WorkerOnly;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * The delay runs from the end of a purge to the next one; its upper bound keeps the 24 h promised
 * to users.
 */
@WorkerOnly
@ConfigurationProperties(prefix = "emploi-go.purge", ignoreUnknownFields = false)
public record PurgeProperties(Duration interval) {
    static final Duration MINIMUM_INTERVAL = Duration.ofMinutes(1);
    static final Duration MAXIMUM_INTERVAL = Duration.ofHours(1);

    public PurgeProperties {
        if (interval == null
                || interval.compareTo(MINIMUM_INTERVAL) < 0
                || interval.compareTo(MAXIMUM_INTERVAL) > 0) {
            throw new IllegalArgumentException(
                    "emploi-go.purge.interval must be between "
                            + MINIMUM_INTERVAL
                            + " and "
                            + MAXIMUM_INTERVAL);
        }
    }
}
