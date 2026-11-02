package fr.emploigo.auth.domain;

import java.time.Duration;

/**
 * How long the temporary authentication data outlives its expiry before the purge deletes it. The
 * expiry is checked at every use, so these delays only bound storage. See docs/backend/donnees.md.
 */
public final class TemporaryRetention {
    /** A registration that is no longer pending (confirmed or expired), and its links. */
    public static final Duration REGISTRATION = Duration.ofDays(7);

    /** A request to recover a password, whatever its state. */
    public static final Duration PASSWORD_RESET = Duration.ofDays(7);

    public static final Duration GOOGLE_FLOW = Duration.ofHours(23);

    /** A Google authentication that waits for the choice of the young person. */
    public static final Duration GOOGLE_PENDING = Duration.ofHours(23);

    public static final Duration GOOGLE_VERIFICATION = Duration.ofDays(7);

    private TemporaryRetention() {}
}
