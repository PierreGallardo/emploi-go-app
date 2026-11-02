package fr.emploigo.auth.application.registration;

import java.time.Duration;
import java.util.Optional;

/**
 * The attempts of a code that no registration of the page can confirm: a page that does not hold
 * the key of the registration, or an email with no pending registration (an account, or nothing).
 * They are counted by email and by key as sent (the total and the hour of the page that holds the
 * email, by email: see {@link #takeOver}), and answered like the page of a registration is
 * answered by the database: the same refusal four times, then "too many attempts", then an hour of
 * waiting at every 15th failure, and "expired" once the last code issued for the email is older
 * than a code lives. An answer therefore never tells whether the email has a pending registration,
 * an account or nothing.
 *
 * <p>Each code issued by a page dates the epoch of that page, and only of that page: a page that
 * never asked for a code is answered the same whatever the email, and a page whose registration was
 * replaced is expired by its own code, as a page of a registration would be.
 */
public interface ConfirmationAttemptLimiter {
    enum State {
        WRONG,
        SPENT,
        WAITING,
        EXPIRED,
        UNAVAILABLE
    }

    record Answer(State state, Duration delay) {}

    /** Counts a wrong code of the page that holds {@code confirmationKey}. */
    Answer fail(String emailKey, String confirmationKey);

    /** The wait left for a page that may not ask for a new code, if any. */
    Optional<Duration> waiting(String emailKey, String confirmationKey);

    /**
     * A code was issued for the page that holds {@code confirmationKey}: its attempts start over.
     */
    void codeIssued(String emailKey, String confirmationKey);

    /**
     * A signup kept no registration for the email (it has an account): from now on this page holds
     * the email, as a new signup takes a registration over. The failures and the hour of the page
     * that holds the email are counted on the email, so that a new page inherits them; any other
     * page keeps counters of its own, and a third party cannot block the holder of a free email.
     */
    void takeOver(String emailKey, String confirmationKey);
}
