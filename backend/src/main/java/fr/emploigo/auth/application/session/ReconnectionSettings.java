package fr.emploigo.auth.application.session;

import java.time.Duration;

/**
 * The tolerance of a reconnection: during it, a previous secret replayed after a rotation is served
 * like the current one and rotates again, because a lost response is not a theft.
 */
public record ReconnectionSettings(Duration reuseTolerance) {
    private static final Duration MINIMUM = Duration.ofSeconds(1);
    private static final Duration MAXIMUM = Duration.ofMinutes(1);

    public ReconnectionSettings {
        if (reuseTolerance.compareTo(MINIMUM) < 0 || reuseTolerance.compareTo(MAXIMUM) > 0)
            throw new IllegalStateException(
                    "La tolérance emploi-go.auth.reconnection-reuse-tolerance doit rester entre 1 s et 1 min.");
    }
}
