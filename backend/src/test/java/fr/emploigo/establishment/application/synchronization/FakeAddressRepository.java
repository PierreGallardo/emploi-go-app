package fr.emploigo.establishment.application.synchronization;

import fr.emploigo.establishment.domain.Street;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** The streets in memory, with their publication; empty at first. */
public final class FakeAddressRepository implements AddressRepository {
    private List<Street> streets = List.of();
    private Instant publishedAt;

    public List<Street> streets() {
        return streets;
    }

    @Override
    public Optional<Instant> findPublishedAt() {
        return Optional.ofNullable(publishedAt);
    }

    @Override
    public void replace(List<Street> streets, Instant publishedAt, Instant importedAt) {
        this.streets = List.copyOf(streets);
        this.publishedAt = publishedAt;
    }
}
