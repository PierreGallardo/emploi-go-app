package fr.emploigo.establishment.application.synchronization;

import fr.emploigo.establishment.domain.TransitStop;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** The stops of the Divia network, with the publication of the feed they come from. */
public interface TransitStopRepository {
    /** When the feed of the stops in place was published; empty before the first import. */
    Optional<Instant> findPublishedAt();

    /** Replaces every stop by these, in one transaction. */
    void replace(List<TransitStop> stops, Instant publishedAt, Instant importedAt);
}
