package fr.emploigo.establishment.infrastructure.synchronization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import fr.emploigo.establishment.application.synchronization.TrainingOrganizationRepository;
import fr.emploigo.establishment.domain.Siret;
import fr.emploigo.support.SqlFailure;
import fr.emploigo.support.TestDatabase;
import fr.emploigo.support.WorkerTest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/** The replacement of the training organisations on PostgreSQL. */
@WorkerTest
class JdbcTrainingOrganizationRepositoryIT {
    private static final Instant PUBLISHED = Instant.parse("2026-10-23T09:34:39Z");
    private static final Instant REPUBLISHED = Instant.parse("2026-10-30T09:00:00Z");
    private static final Instant IMPORTED = Instant.parse("2026-10-25T03:20:00.123456Z");
    private static final Siret FIRST = new Siret("12345678900012");
    private static final Siret SECOND = new Siret("22345678900012");

    @Autowired TrainingOrganizationRepository trainers;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void emptyTheTables() {
        TestDatabase.reset(jdbc);
    }

    @Test
    void organizationsAreWrittenWithThePublicationOfTheList() {
        trainers.replace(Set.of(FIRST, SECOND), PUBLISHED, IMPORTED);

        assertThat(trainers.findSirets()).containsExactlyInAnyOrder(FIRST, SECOND);
        assertThat(trainers.findPublishedAt()).contains(PUBLISHED);
    }

    @Test
    void newListReplacesEveryOrganization() {
        trainers.replace(Set.of(FIRST), PUBLISHED, IMPORTED);

        trainers.replace(Set.of(SECOND), REPUBLISHED, IMPORTED);

        assertThat(trainers.findSirets()).containsExactly(SECOND);
        assertThat(trainers.findPublishedAt()).contains(REPUBLISHED);
    }

    @Test
    void listLongerThanABatchIsWrittenWhole() {
        Set<Siret> many =
                IntStream.range(0, 12_000)
                        .mapToObj(index -> new Siret("%014d".formatted(index)))
                        .collect(Collectors.toSet());

        trainers.replace(many, PUBLISHED, IMPORTED);

        assertThat(trainers.findSirets()).hasSize(12_000);
    }

    @Test
    void failedReplacementKeepsTheOrganizationsAndThePublicationInPlace() {
        trainers.replace(Set.of(FIRST), PUBLISHED, IMPORTED);

        try (var failure = SqlFailure.inject(jdbc, "training_organizations", "BEFORE INSERT")) {
            assertThatThrownBy(() -> trainers.replace(Set.of(SECOND), REPUBLISHED, IMPORTED))
                    .isInstanceOf(DataAccessException.class);
        }

        assertThat(trainers.findSirets()).containsExactly(FIRST);
        assertThat(trainers.findPublishedAt()).contains(PUBLISHED);
    }

    @Test
    void noPublicationIsKnownBeforeTheFirstImport() {
        assertThat(trainers.findPublishedAt()).isEmpty();
    }
}
