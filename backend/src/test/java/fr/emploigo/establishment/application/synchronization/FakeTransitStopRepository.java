package fr.emploigo.establishment.application.synchronization;

import fr.emploigo.establishment.domain.TransitStop;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** The stops in memory, with their publication; empty at first. */
public final class FakeTransitStopRepository implements TransitStopRepository {
    private List<TransitStop> stops = List.of();
    private Instant publishedAt;
    private Instant importedAt;

    public List<TransitStop> stops() {
        return stops;
    }

    public Instant importedAt() {
        return importedAt;
    }

    @Override
    public Optional<Instant> findPublishedAt() {
        return Optional.ofNullable(publishedAt);
    }

    @Override
    public void replace(List<TransitStop> stops, Instant publishedAt, Instant importedAt) {
        this.stops = List.copyOf(stops);
        this.publishedAt = publishedAt;
        this.importedAt = importedAt;
    }
}
