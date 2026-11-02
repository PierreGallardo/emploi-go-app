package fr.emploigo.auth.application.quota;

import java.time.Duration;

/**
 * Counts an attempt and says whether it may go on. An adapter never throws: a failure of the
 * counters is {@link Unavailable}.
 */
public interface AuthenticationRateLimiter {
    sealed interface Outcome permits Allowed, Limited, Unavailable {}

    record Allowed() implements Outcome {}

    /**
     * The attempt is refused: {@code cause} tells a volume limit from the delay between two sends.
     */
    record Limited(Duration retryAfter, Cause cause) implements Outcome {}

    /** The counters cannot answer: the attempt was not counted. */
    record Unavailable() implements Outcome {}

    enum Cause {
        VOLUME,
        SEND_DELAY
    }

    Outcome check(QuotaScope scope, String identity, String address, QuotaLimits limits);

    /**
     * Counts an attempt of an action that has no limit by address ({@link
     * QuotaScope#identityOnly()}).
     */
    Outcome checkIdentity(QuotaScope scope, String identity, IdentityLimits limits);
}
