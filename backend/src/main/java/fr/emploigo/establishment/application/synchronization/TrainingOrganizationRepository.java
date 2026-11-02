package fr.emploigo.establishment.application.synchronization;

import fr.emploigo.establishment.domain.Siret;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

/** The establishments declared as training organisations, with the publication of their list. */
public interface TrainingOrganizationRepository {
    /** When the list in place was published; empty before the first import. */
    Optional<Instant> findPublishedAt();

    Set<Siret> findSirets();

    /** Replaces the whole list by these establishments, in one transaction. */
    void replace(Set<Siret> sirets, Instant publishedAt, Instant importedAt);
}
