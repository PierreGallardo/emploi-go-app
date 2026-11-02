package fr.emploigo.establishment.infrastructure.synchronization;

import static fr.emploigo.establishment.domain.TestDirectoryEstablishment.CLASSIFIED;
import static fr.emploigo.establishment.domain.TestDirectoryEstablishment.retained;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import fr.emploigo.establishment.domain.Assessment;
import fr.emploigo.establishment.domain.HouseNumber;
import fr.emploigo.establishment.domain.Position;
import fr.emploigo.establishment.domain.Siret;
import fr.emploigo.establishment.domain.Street;
import fr.emploigo.establishment.domain.TestDirectoryEstablishment;
import fr.emploigo.establishment.domain.TransitLine;
import fr.emploigo.establishment.domain.TransitMode;
import fr.emploigo.establishment.domain.TransitStop;
import fr.emploigo.establishment.infrastructure.MapRoles;
import fr.emploigo.support.TestDatabase;
import fr.emploigo.support.WorkerTest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/** The imports of the worker run with exactly the rights db/grants/map.sql gives it. */
@WorkerTest
class ImportRolePrivilegesIT {
    private static final Instant YESTERDAY = Instant.parse("2026-10-24T01:30:00Z");
    private static final Instant RUN = Instant.parse("2026-10-25T01:30:00Z");

    @Autowired JdbcTemplate jdbc;

    private MapRoles roles;
    private JdbcTemplate worker;

    @BeforeEach
    void rolesWithTheRightsOfTheScript() {
        TestDatabase.reset(jdbc);
        roles = new MapRoles(jdbc);
        roles.create();
        worker = new JdbcTemplate(MapRoles.connect(MapRoles.WORKER));
    }

    @AfterEach
    void dropTheRoles() {
        roles.drop();
    }

    @Test
    void importOfTheEstablishmentsWritesWithTheRightsOfTheWorker() {
        var establishments = new JdbcEstablishmentRepository(worker);
        establishments.save(
                List.of(
                        assess(retained().siret("12345678900012")),
                        assess(retained().siret("22345678900012")),
                        assess(retained().siret("32345678900012"))),
                YESTERDAY);

        establishments.save(
                List.of(
                        assess(retained().siret("12345678900012").activities(null, null)),
                        assess(retained().siret("22345678900012").diffusible(false)),
                        assess(retained().siret("42345678900012"))),
                RUN);
        int notFound = establishments.markNotFound(RUN);
        int purged = establishments.deleteExcluded(RUN.plusSeconds(1), 10);

        assertThat(notFound).isOne();
        assertThat(purged).isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT siret FROM establishments", String.class))
                .containsExactly("42345678900012");
    }

    @Test
    void workerReadsWhetherAnEstablishmentWasEverImported() {
        var establishments = new JdbcEstablishmentRepository(worker);
        boolean before = establishments.isEmpty();
        establishments.save(List.of(assess(retained())), RUN);

        assertThat(before).as("before the import").isTrue();
        assertThat(establishments.isEmpty()).as("after the import").isFalse();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "visible = TRUE",
                "description = 'Description fictive'",
                "image_file = 'fictif.webp'",
                "jobs_url = 'https://example.invalid/emplois'"
            })
    void workerCannotWriteWhatTheMissionLocaleFills(String change) {
        new JdbcEstablishmentRepository(worker)
                .save(List.of(assess(retained().siret("12345678900012"))), RUN);

        assertThatThrownBy(() -> worker.update("UPDATE establishments SET " + change))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void stopsAddressesAndTrainingOrganizationsAreReplacedWithTheRightsOfTheWorker() {
        MapRoles.Jpa jpa = roles.jpa(MapRoles.WORKER, getClass().getPackageName());
        var repositories = new JpaRepositoryFactory(jpa.entities());
        var publications =
                new DatasetPublications(
                        repositories.getRepository(SpringDataDatasetPublicationRepository.class));
        var stops =
                new JpaTransitStopRepository(
                        repositories.getRepository(SpringDataTransitStopRepository.class),
                        repositories.getRepository(SpringDataTransitLineRepository.class),
                        publications,
                        jpa.entities());
        var addresses =
                new JpaAddressRepository(
                        repositories.getRepository(SpringDataAddressRepository.class),
                        publications,
                        jpa.entities());
        var trainers = new JdbcTrainingOrganizationRepository(worker, publications);
        var trainer = new Siret("12345678900012");
        var here = new Position(5.04, 47.32);
        var stop = new TransitStop("Darcy", here, List.of(new TransitLine("T1", TransitMode.TRAM)));
        var street =
                new Street("21231", "Rue Fictive", here, List.of(new HouseNumber(1, null, here)));

        jpa.transactions()
                .executeWithoutResult(
                        status -> {
                            stops.replace(List.of(stop), YESTERDAY, RUN);
                            stops.replace(List.of(stop), RUN, RUN);
                            addresses.replace(List.of(street), YESTERDAY, RUN);
                            addresses.replace(List.of(street), RUN, RUN);
                            trainers.replace(Set.of(trainer), YESTERDAY, RUN);
                            trainers.replace(Set.of(trainer), RUN, RUN);
                        });

        assertThat(jdbc.queryForList("SELECT name FROM transit_stops", String.class))
                .containsExactly("Darcy");
        assertThat(jdbc.queryForList("SELECT name FROM address_streets", String.class))
                .containsExactly("Rue Fictive");
        assertThat(trainers.findSirets()).containsExactly(trainer);
        assertThat(trainers.findPublishedAt()).contains(RUN);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM dataset_publications", Integer.class))
                .isEqualTo(3);
    }

    @Test
    void workerReadsTheReferenceDataItImportsBy() {
        MapRoles.Jpa jpa = roles.jpa(MapRoles.WORKER, getClass().getPackageName());
        var repositories = new JpaRepositoryFactory(jpa.entities());

        var activities =
                new JpaActivityRepository(
                        repositories.getRepository(SpringDataActivityRepository.class));
        var communes =
                new JpaCommuneRepository(
                        repositories.getRepository(SpringDataCommuneRepository.class));

        assertThat(activities.findClassified()).contains(CLASSIFIED.iterator().next());
        assertThat(communes.findCodes()).hasSize(23);
    }

    private static Assessment assess(TestDirectoryEstablishment establishment) {
        return establishment.build().assess(CLASSIFIED, Set.of());
    }
}
