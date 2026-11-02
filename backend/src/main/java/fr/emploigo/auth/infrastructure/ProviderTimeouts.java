package fr.emploigo.auth.infrastructure;

import org.springframework.boot.convert.DurationStyle;
import org.springframework.core.env.Environment;

import java.time.Duration;

public final class ProviderTimeouts {
    private static final Duration MINIMUM = Duration.ofMillis(100);
    private static final Duration MAXIMUM = Duration.ofSeconds(30);

    private ProviderTimeouts() {}

    public static Duration read(Environment environment, String property, Duration byDefault) {
        Duration timeout = duration(environment, property, byDefault);
        if (timeout.compareTo(MINIMUM) < 0 || timeout.compareTo(MAXIMUM) > 0)
            throw new IllegalStateException(
                    "Le délai " + property + " doit rester entre 100 ms et 30 s.");
        return timeout;
    }

    private static Duration duration(Environment environment, String property, Duration byDefault) {
        String value = environment.getProperty(property);
        return value == null || value.isBlank() ? byDefault : parse(value, property);
    }

    private static Duration parse(String value, String property) {
        try {
            return DurationStyle.detectAndParse(value.trim());
        } catch (IllegalArgumentException error) {
            throw new IllegalStateException(
                    "La propriété " + property + " n'est pas une durée valide.");
        }
    }
}
