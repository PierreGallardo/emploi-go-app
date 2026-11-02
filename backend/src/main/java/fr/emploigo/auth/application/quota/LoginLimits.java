package fr.emploigo.auth.application.quota;

import java.time.Duration;

/**
 * The limits of the login: the attempts of an address, and the failures of an identity, which get a
 * growing delay instead of a limit.
 *
 * @param addressLimit attempts per client network within the window, whatever their outcome
 */
public record LoginLimits(int addressLimit, Duration window, FailureDelay delay) {
    /**
     * The wait that follows a failure: the first {@code freeFailures - 1} failures of an identity
     * have none; from the {@code freeFailures}-th on, the identity is refused for {@code
     * min(maxDelay, baseDelay * growthFactor^(n - freeFailures))}, n being the failures counted.
     *
     * @param counterTtl the life of the failures of an identity, renewed by each failure; longer
     *     than {@code maxDelay}, so the counter outlives the delay it sets
     */
    public record FailureDelay(
            int freeFailures,
            Duration baseDelay,
            double growthFactor,
            Duration maxDelay,
            Duration counterTtl) {}
}
