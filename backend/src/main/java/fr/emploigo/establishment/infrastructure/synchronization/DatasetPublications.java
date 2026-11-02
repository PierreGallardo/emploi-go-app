package fr.emploigo.establishment.infrastructure.synchronization;

import fr.emploigo.config.WorkerOnly;
import fr.emploigo.establishment.infrastructure.synchronization.DatasetPublicationJpaEntity.Dataset;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;

/**
 * The one access to the publications of the data sets, in the transaction of the adapter that
 * replaces a data set: each one reads and writes its own.
 */
@Component
@WorkerOnly
class DatasetPublications {
    private final SpringDataDatasetPublicationRepository publications;

    DatasetPublications(SpringDataDatasetPublicationRepository publications) {
        this.publications = publications;
    }

    Optional<Instant> publishedAt(Dataset dataset) {
        return publications.findById(dataset).map(DatasetPublicationJpaEntity::publishedAt);
    }

    void record(Dataset dataset, Instant publishedAt, Instant importedAt) {
        publications.saveAndFlush(DatasetPublicationJpaEntity.of(dataset, publishedAt, importedAt));
    }
}
