package fr.emploigo.establishment.infrastructure.synchronization;

import fr.emploigo.config.WorkerOnly;
import fr.emploigo.establishment.application.synchronization.TransitStopRepository;
import fr.emploigo.establishment.domain.TransitLine;
import fr.emploigo.establishment.domain.TransitStop;
import fr.emploigo.establishment.infrastructure.synchronization.DatasetPublicationJpaEntity.Dataset;

import jakarta.persistence.EntityManager;

import org.hibernate.Session;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
@WorkerOnly
class JpaTransitStopRepository implements TransitStopRepository {
    private static final int BATCH_SIZE = 500;

    private final SpringDataTransitStopRepository stops;
    private final SpringDataTransitLineRepository lines;
    private final DatasetPublications publications;
    private final EntityManager entities;

    JpaTransitStopRepository(
            SpringDataTransitStopRepository stops,
            SpringDataTransitLineRepository lines,
            DatasetPublications publications,
            EntityManager entities) {
        this.stops = stops;
        this.lines = lines;
        this.publications = publications;
        this.entities = entities;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Instant> findPublishedAt() {
        return publications.publishedAt(Dataset.TRANSIT_STOPS);
    }

    @Override
    @Transactional
    public void replace(List<TransitStop> served, Instant publishedAt, Instant importedAt) {
        entities.unwrap(Session.class).setJdbcBatchSize(BATCH_SIZE);
        stops.deleteAllInBatch();
        lines.deleteAllInBatch();
        Map<String, TransitLine> network = new LinkedHashMap<>();
        served.forEach(
                stop -> stop.lines().forEach(line -> network.putIfAbsent(line.name(), line)));
        network.values().forEach(line -> entities.persist(TransitLineJpaEntity.from(line)));
        entities.flush();
        for (int index = 0; index < served.size(); index++) {
            entities.persist(TransitStopJpaEntity.from(served.get(index)));
            if ((index + 1) % BATCH_SIZE == 0) {
                entities.flush();
                entities.clear();
            }
        }
        publications.record(Dataset.TRANSIT_STOPS, publishedAt, importedAt);
        entities.clear();
    }
}
