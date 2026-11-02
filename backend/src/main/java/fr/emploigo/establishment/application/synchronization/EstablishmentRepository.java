package fr.emploigo.establishment.application.synchronization;

import fr.emploigo.establishment.domain.Assessment;

import java.time.Instant;
import java.util.List;

/**
 * The establishments of the directory; never writes the data that we enter ourselves. Each call is
 * its own transaction, so that the import holds none during its calls to the directory.
 */
public interface EstablishmentRepository {
    /**
     * Applies the assessments of one page in one transaction: a retained establishment is written
     * with the State data, an excluded one that exists keeps its data with its reason, a withheld
     * one is deleted. Every establishment written is seen at {@code seenAt}; an excluded one keeps
     * the date of its first exclusion.
     */
    void save(List<Assessment> assessments, Instant seenAt);

    /**
     * Excludes as not found the establishments seen before {@code seenBefore}; returns how many.
     */
    int markNotFound(Instant seenBefore);

    /**
     * Deletes at most {@code limit} establishments excluded before {@code excludedBefore}, the
     * oldest first, in one transaction; returns how many.
     */
    int deleteExcluded(Instant excludedBefore, int limit);

    /** Whether the table holds no establishment, as before the first import. */
    boolean isEmpty();
}
