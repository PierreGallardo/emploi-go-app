package fr.emploigo.establishment.infrastructure.synchronization;

import fr.emploigo.config.WorkerOnly;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;
import java.time.LocalTime;

/**
 * The daily import of the establishments: the hour of Paris it starts at, the Sirene directory it
 * reads and the API key it asks with. Without key the worker starts, and the import fails with a
 * clear cause.
 */
@WorkerOnly
@ConfigurationProperties(
        prefix = "emploi-go.establishment-synchronization",
        ignoreUnknownFields = false)
public record EstablishmentSynchronizationProperties(
        LocalTime dailyAt,
        URI directoryUrl,
        String apiKey,
        Duration connectTimeout,
        Duration responseTimeout) {
    static final Duration MAXIMUM_TIMEOUT = Duration.ofMinutes(1);

    public EstablishmentSynchronizationProperties {
        if (dailyAt == null)
            throw new IllegalArgumentException(
                    "emploi-go.establishment-synchronization.daily-at is required");
        // Plain HTTP only reaches a local fake: the names and addresses read are shown to the
        // users.
        if (directoryUrl == null
                || !"https".equals(directoryUrl.getScheme())
                        && !("http".equals(directoryUrl.getScheme())
                                && "127.0.0.1".equals(directoryUrl.getHost())))
            throw new IllegalArgumentException(
                    "emploi-go.establishment-synchronization.directory-url must be an HTTPS URL");
        apiKey = apiKey == null || apiKey.isBlank() ? null : apiKey.strip();
        // The JDK quotes a header value it refuses: a key that is not plain text must not reach it.
        if (apiKey != null && !apiKey.matches("[\\x21-\\x7E]+"))
            throw new IllegalArgumentException(
                    "emploi-go.establishment-synchronization.api-key must be printable ASCII"
                            + " without spaces");
        requireTimeout("connect-timeout", connectTimeout);
        requireTimeout("response-timeout", responseTimeout);
    }

    /** The key never appears in a log or a message. */
    @Override
    public String toString() {
        return "EstablishmentSynchronizationProperties[dailyAt="
                + dailyAt
                + ", directoryUrl="
                + directoryUrl
                + ", apiKey="
                + (apiKey == null ? "absent" : "***")
                + ", connectTimeout="
                + connectTimeout
                + ", responseTimeout="
                + responseTimeout
                + "]";
    }

    private static void requireTimeout(String name, Duration timeout) {
        if (timeout == null
                || timeout.isNegative()
                || timeout.isZero()
                || timeout.compareTo(MAXIMUM_TIMEOUT) > 0)
            throw new IllegalArgumentException(
                    "emploi-go.establishment-synchronization."
                            + name
                            + " must be positive and at most "
                            + MAXIMUM_TIMEOUT);
    }
}
