package fr.emploigo.establishment.infrastructure.synchronization;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** The publication of the file a data set was last imported from; each import writes its own. */
@Entity
@Table(name = "dataset_publications")
public class DatasetPublicationJpaEntity {
    /** The data sets copied from an open data file. */
    enum Dataset {
        TRANSIT_STOPS,
        ADDRESSES,
        TRAINING_ORGANIZATIONS
    }

    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "dataset", nullable = false, length = 32)
    private Dataset dataset;

    @Column(name = "published_at", nullable = false)
    private Instant publishedAt;

    @Column(name = "imported_at", nullable = false)
    private Instant importedAt;

    protected DatasetPublicationJpaEntity() {}

    static DatasetPublicationJpaEntity of(
            Dataset dataset, Instant publishedAt, Instant importedAt) {
        var entity = new DatasetPublicationJpaEntity();
        entity.dataset = dataset;
        entity.publishedAt = publishedAt;
        entity.importedAt = importedAt;
        return entity;
    }

    Instant publishedAt() {
        return publishedAt;
    }
}
