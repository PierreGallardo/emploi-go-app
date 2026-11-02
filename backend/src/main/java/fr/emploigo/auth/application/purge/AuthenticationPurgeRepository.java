package fr.emploigo.auth.application.purge;

import java.time.Instant;

/**
 * The batches of the purge. Each method runs one bounded batch in its own transaction, so that no
 * lock is held between batches. A batch smaller than {@link #BATCH_SIZE} is the last one.
 */
public interface AuthenticationPurgeRepository {
    /**
     * The rows handled by one statement: bounded, so that no statement holds many locks or reads
     * many rows.
     */
    int BATCH_SIZE = 100;

    int expireRegistrations(Instant expiredBy);

    /**
     * Deletes the registrations that are not pending and expired before {@code expiredBefore}, with
     * their links.
     */
    int deleteRegistrations(Instant expiredBefore);

    int deletePasswordResets(Instant expiredBefore);

    int deleteGoogleFlows(Instant expiredBefore);

    int deleteGooglePending(Instant expiredBefore);

    int deleteGoogleVerifications(Instant expiredBefore);

    /** Deletes the expired families of reconnection, whether revoked or not. */
    int deleteReconnections(Instant expiredBefore);
}
