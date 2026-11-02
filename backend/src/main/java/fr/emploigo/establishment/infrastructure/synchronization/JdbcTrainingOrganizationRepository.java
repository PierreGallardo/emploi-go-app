package fr.emploigo.establishment.infrastructure.synchronization;

import fr.emploigo.config.WorkerOnly;
import fr.emploigo.establishment.application.synchronization.TrainingOrganizationRepository;
import fr.emploigo.establishment.domain.Siret;
import fr.emploigo.establishment.infrastructure.synchronization.DatasetPublicationJpaEntity.Dataset;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Repository
@WorkerOnly
class JdbcTrainingOrganizationRepository implements TrainingOrganizationRepository {
    private static final int BATCH_SIZE = 5_000;

    private final JdbcTemplate jdbc;
    private final DatasetPublications publications;

    JdbcTrainingOrganizationRepository(JdbcTemplate jdbc, DatasetPublications publications) {
        this.jdbc = jdbc;
        this.publications = publications;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Instant> findPublishedAt() {
        return publications.publishedAt(Dataset.TRAINING_ORGANIZATIONS);
    }

    @Override
    @Transactional(readOnly = true)
    public Set<Siret> findSirets() {
        return jdbc.queryForList("SELECT siret FROM training_organizations", String.class).stream()
                .map(Siret::new)
                .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    @Transactional
    public void replace(Set<Siret> sirets, Instant publishedAt, Instant importedAt) {
        jdbc.update("DELETE FROM training_organizations");
        jdbc.batchUpdate(
                "INSERT INTO training_organizations (siret) VALUES (?)",
                sirets,
                BATCH_SIZE,
                (statement, siret) -> statement.setString(1, siret.value()));
        publications.record(Dataset.TRAINING_ORGANIZATIONS, publishedAt, importedAt);
    }
}
