package fr.emploigo.establishment.infrastructure.synchronization;

import fr.emploigo.config.WorkerOnly;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;
import java.time.LocalTime;

/**
 * The daily imports of the open data files: the Divia stops, the addresses and the training
 * organisations. Each starts at its hour of Paris and downloads its file whole within the
 * timeouts.
 */
@WorkerOnly
@ConfigurationProperties(prefix = "emploi-go.dataset-synchronization", ignoreUnknownFields = false)
public record DatasetSynchronizationProperties(
        Duration connectTimeout,
        Duration downloadTimeout,
        Source transitStops,
        Source addresses,
        Source trainingOrganizations) {
    private static final String PREFIX = "emploi-go.dataset-synchronization.";
    static final Duration MAXIMUM_CONNECT_TIMEOUT = Duration.ofMinutes(1);
    static final Duration MAXIMUM_DOWNLOAD_TIMEOUT = Duration.ofMinutes(10);

    /** One file: the hour of Paris its import starts at, and where it is published. */
    public record Source(LocalTime dailyAt, URI url) {}

    public DatasetSynchronizationProperties {
        requireTimeout("connect-timeout", connectTimeout, MAXIMUM_CONNECT_TIMEOUT);
        requireTimeout("download-timeout", downloadTimeout, MAXIMUM_DOWNLOAD_TIMEOUT);
        requireSource("transit-stops", transitStops);
        requireSource("addresses", addresses);
        requireSource("training-organizations", trainingOrganizations);
    }

    private static void requireTimeout(String name, Duration timeout, Duration maximum) {
        if (timeout == null
                || timeout.isNegative()
                || timeout.isZero()
                || timeout.compareTo(maximum) > 0)
            throw new IllegalArgumentException(
                    PREFIX + name + " must be positive and at most " + maximum);
    }

    private static void requireSource(String name, Source source) {
        if (source == null || source.dailyAt() == null)
            throw new IllegalArgumentException(PREFIX + name + ".daily-at is required");
        URI url = source.url();
        // Plain HTTP only reaches a local fake: what the files hold is shown to the users.
        if (url == null
                || !"https".equals(url.getScheme())
                        && !("http".equals(url.getScheme()) && "127.0.0.1".equals(url.getHost())))
            throw new IllegalArgumentException(PREFIX + name + ".url must be an HTTPS URL");
    }
}
