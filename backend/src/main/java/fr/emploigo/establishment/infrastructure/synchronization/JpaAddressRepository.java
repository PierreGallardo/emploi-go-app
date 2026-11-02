package fr.emploigo.establishment.infrastructure.synchronization;

import fr.emploigo.config.WorkerOnly;
import fr.emploigo.establishment.application.synchronization.AddressRepository;
import fr.emploigo.establishment.domain.Street;
import fr.emploigo.establishment.infrastructure.synchronization.DatasetPublicationJpaEntity.Dataset;

import jakarta.persistence.EntityManager;

import org.hibernate.Session;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
@WorkerOnly
class JpaAddressRepository implements AddressRepository {
    private static final int BATCH_SIZE = 500;

    /** About 15 numbers each: a flush writes a few thousand rows. */
    private static final int STREETS_PER_FLUSH = 200;

    private final SpringDataAddressRepository streets;
    private final DatasetPublications publications;
    private final EntityManager entities;

    JpaAddressRepository(
            SpringDataAddressRepository streets,
            DatasetPublications publications,
            EntityManager entities) {
        this.streets = streets;
        this.publications = publications;
        this.entities = entities;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Instant> findPublishedAt() {
        return publications.publishedAt(Dataset.ADDRESSES);
    }

    @Override
    @Transactional
    public void replace(List<Street> metropolis, Instant publishedAt, Instant importedAt) {
        entities.unwrap(Session.class).setJdbcBatchSize(BATCH_SIZE);
        streets.deleteAllInBatch();
        for (int index = 0; index < metropolis.size(); index++) {
            entities.persist(AddressStreetJpaEntity.from(metropolis.get(index)));
            if ((index + 1) % STREETS_PER_FLUSH == 0) {
                entities.flush();
                entities.clear();
            }
        }
        publications.record(Dataset.ADDRESSES, publishedAt, importedAt);
        entities.clear();
    }
}
