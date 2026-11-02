package fr.emploigo.establishment.application.synchronization;

import fr.emploigo.establishment.domain.Siret;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

/** The training organisations in memory, with the publication of their list. */
public final class FakeTrainingOrganizationRepository implements TrainingOrganizationRepository {
    private Set<Siret> sirets = Set.of();
    private Instant publishedAt;

    public FakeTrainingOrganizationRepository with(Set<Siret> sirets, Instant publishedAt) {
        this.sirets = sirets;
        this.publishedAt = publishedAt;
        return this;
    }

    @Override
    public Optional<Instant> findPublishedAt() {
        return Optional.ofNullable(publishedAt);
    }

    @Override
    public Set<Siret> findSirets() {
        return sirets;
    }

    @Override
    public void replace(Set<Siret> sirets, Instant publishedAt, Instant importedAt) {
        this.sirets = sirets;
        this.publishedAt = publishedAt;
    }
}
