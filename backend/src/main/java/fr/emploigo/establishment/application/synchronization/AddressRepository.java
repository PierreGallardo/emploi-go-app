package fr.emploigo.establishment.application.synchronization;

import fr.emploigo.establishment.domain.Street;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** The streets of the metropolis and their numbers, with the publication of their file. */
public interface AddressRepository {
    /** When the file of the streets in place was published; empty before the first import. */
    Optional<Instant> findPublishedAt();

    /** Replaces every street by these, in one transaction. */
    void replace(List<Street> streets, Instant publishedAt, Instant importedAt);
}
