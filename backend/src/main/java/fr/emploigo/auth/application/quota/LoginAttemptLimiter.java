package fr.emploigo.auth.application.quota;

import java.time.Duration;

/**
 * Counts the login attempts, and only the failures of an identity, before the password is checked:
 * {@link #begin} counts an attempt as a failure atomically, so parallel attempts cannot all pass.
 * An adapter never throws: a failure of the counters is {@link Unavailable}, and the recordings say
 * whether the counters took them.
 */
public interface LoginAttemptLimiter {
    sealed interface Admission permits Admitted, Limited, Unavailable {}

    /** The attempt may check its password. It is already counted as a failure. */
    record Admitted() implements Admission {}

    /**
     * A refusal by the identity's delay counts nowhere; a refusal by the address counts in the
     * address only.
     */
    record Limited(Duration retryAfter) implements Admission {}

    /** The counters cannot answer: the attempt was not counted. */
    record Unavailable() implements Admission {}

    Admission begin(String identity, String address, LoginLimits limits);

    /**
     * Forgets the failures and delay of an identity: after a right password, or after a recovered
     * password. The address counters are not touched.
     */
    boolean forget(String identity);
}
