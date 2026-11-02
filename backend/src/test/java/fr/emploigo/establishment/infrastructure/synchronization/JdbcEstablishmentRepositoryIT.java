package fr.emploigo.establishment.infrastructure.synchronization;

import static fr.emploigo.establishment.domain.TestDirectoryEstablishment.CLASSIFIED;
import static fr.emploigo.establishment.domain.TestDirectoryEstablishment.retained;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import fr.emploigo.establishment.application.synchronization.EstablishmentRepository;
import fr.emploigo.establishment.domain.Assessment;
import fr.emploigo.establishment.domain.TestDirectoryEstablishment;
import fr.emploigo.support.TestDatabase;
import fr.emploigo.support.WorkerTest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The writes of the import on PostgreSQL, with PostGIS and the constraints of the table. */
@WorkerTest
class JdbcEstablishmentRepositoryIT {
    private static final Instant YESTERDAY = Instant.parse("2026-10-24T01:30:00Z");
    private static final Instant RUN = Instant.parse("2026-10-25T01:30:00.123456Z");
    private static final String SIRET = "12345678900012";

    @Autowired EstablishmentRepository establishments;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void emptyTheTables() {
        TestDatabase.reset(jdbc);
    }

    @Test
    void retainedEstablishmentIsAddedHiddenWithItsStateDataAndItsLocation() {
        establishments.save(List.of(assess(retained())), RUN);

        assertThat(
                        jdbc.queryForMap(
                                """
                                SELECT name, activity_nomenclature, activity_code, company_type,
                                       headcount_range, size, exclusion_reason, seen_at, visible,
                                       ST_X(location::geometry) AS x, ST_Y(location::geometry) AS y
                                FROM establishments
                                WHERE siret = ?
                                """,
                                SIRET))
                .containsAllEntriesOf(
                        Map.of(
                                "name", "LE BISTROT FICTIF",
                                "activity_nomenclature", "NAF_2025",
                                "activity_code", "56.11G",
                                "company_type", "COMPANY",
                                "headcount_range", "11",
                                "size", "MEDIUM",
                                "seen_at", Timestamp.from(RUN),
                                "visible", false,
                                "x", 5.04,
                                "y", 47.32))
                .containsEntry("exclusion_reason", null);
    }

    @Test
    void retainedAgainEstablishmentIsUpdatedAndLosesItsExclusionWithoutTouchingOurData() {
        establishments.save(List.of(assess(retained())), YESTERDAY);
        jdbc.update(
                """
                UPDATE establishments
                SET visible = TRUE, description = 'Description fictive',
                    image_file = 'bistrot.webp',
                    jobs_url = 'https://example.invalid/emplois', exclusion_reason = 'NOT_FOUND',
                    excluded_at = now()
                WHERE siret = ?
                """,
                SIRET);

        establishments.save(List.of(assess(retained().signs("NOUVEAU NOM FICTIF"))), RUN);

        assertThat(
                        jdbc.queryForMap(
                                """
                                SELECT name, exclusion_reason, visible, description, image_file,
                                       jobs_url
                                FROM establishments
                                WHERE siret = ?
                                """,
                                SIRET))
                .containsAllEntriesOf(
                        Map.of(
                                "name", "NOUVEAU NOM FICTIF",
                                "visible", true,
                                "description", "Description fictive",
                                "image_file", "bistrot.webp",
                                "jobs_url", "https://example.invalid/emplois"))
                .containsEntry("exclusion_reason", null);
    }

    @Test
    void excludedEstablishmentKeepsItsDataWithItsReason() {
        establishments.save(List.of(assess(retained())), YESTERDAY);

        establishments.save(List.of(assess(notClassified())), RUN);

        assertThat(
                        jdbc.queryForMap(
                                """
                                SELECT name, exclusion_reason, excluded_at, seen_at
                                FROM establishments
                                WHERE siret = ?
                                """,
                                SIRET))
                .isEqualTo(
                        Map.of(
                                "name",
                                "LE BISTROT FICTIF",
                                "exclusion_reason",
                                "EXCLUDED_ACTIVITY",
                                "excluded_at",
                                Timestamp.from(RUN),
                                "seen_at",
                                Timestamp.from(RUN)));
    }

    @Test
    void establishmentExcludedAgainKeepsTheDateOfItsFirstExclusion() {
        establishments.save(List.of(assess(retained())), YESTERDAY);
        establishments.save(List.of(assess(notClassified())), YESTERDAY.plusSeconds(1));

        establishments.save(List.of(assess(withoutName())), RUN);

        assertThat(jdbc.queryForMap("SELECT exclusion_reason, excluded_at FROM establishments"))
                .isEqualTo(
                        Map.of(
                                "exclusion_reason",
                                "NO_SIGN",
                                "excluded_at",
                                Timestamp.from(YESTERDAY.plusSeconds(1))));
    }

    @Test
    void retainedAgainEstablishmentLosesItsExclusionDate() {
        establishments.save(List.of(assess(retained())), YESTERDAY);
        establishments.save(List.of(assess(notClassified())), YESTERDAY.plusSeconds(1));

        establishments.save(List.of(assess(retained())), RUN);

        assertThat(jdbc.queryForObject("SELECT excluded_at FROM establishments", Timestamp.class))
                .isNull();
    }

    @Test
    void purgeDeletesTheOldestExcludedEstablishmentsUpToTheLimit() {
        establishments.save(
                List.of(
                        assess(retained().siret("12345678900012")),
                        assess(retained().siret("22345678900012")),
                        assess(retained().siret("32345678900012")),
                        assess(retained().siret("42345678900012"))),
                YESTERDAY);
        jdbc.update(
                """
                UPDATE establishments
                SET exclusion_reason = 'NOT_FOUND',
                    excluded_at = CASE siret
                        WHEN '12345678900012' THEN TIMESTAMPTZ '2026-01-01 00:00:00Z'
                        WHEN '22345678900012' THEN TIMESTAMPTZ '2026-02-01 00:00:00Z'
                        ELSE TIMESTAMPTZ '2026-10-01 00:00:00Z' END
                WHERE siret <> '42345678900012'
                """);

        int deleted = establishments.deleteExcluded(Instant.parse("2026-09-01T00:00:00Z"), 1);

        assertThat(deleted).isOne();
        assertThat(sirets()).containsExactly("22345678900012", "32345678900012", "42345678900012");
    }

    @Test
    void excludedEstablishmentNeverRetainedIsNotAdded() {
        establishments.save(List.of(assess(withoutName())), RUN);

        assertThat(rows()).isZero();
    }

    @Test
    void withheldEstablishmentIsDeleted() {
        establishments.save(List.of(assess(retained())), YESTERDAY);

        establishments.save(List.of(assess(retained().diffusible(false))), RUN);

        assertThat(rows()).isZero();
    }

    @Test
    void establishmentsNotSeenByTheRunAreMarkedNotFound() {
        establishments.save(
                List.of(
                        assess(retained().siret("12345678900012")),
                        assess(retained().siret("22345678900012"))),
                YESTERDAY);
        establishments.save(List.of(assess(retained().siret("32345678900012"))), RUN);

        int marked = establishments.markNotFound(RUN);

        assertThat(marked).isEqualTo(2);
        assertThat(exclusions())
                .containsExactly(
                        new Exclusion("12345678900012", "NOT_FOUND"),
                        new Exclusion("22345678900012", "NOT_FOUND"),
                        new Exclusion("32345678900012", null));
    }

    @Test
    void establishmentAlreadyNotFoundIsNotCountedAgain() {
        establishments.save(
                List.of(
                        assess(retained().siret("12345678900012")),
                        assess(retained().siret("22345678900012"))),
                YESTERDAY);
        establishments.markNotFound(YESTERDAY.plusSeconds(1));
        establishments.save(
                List.of(assess(notClassified().siret("22345678900012"))), YESTERDAY);

        int marked = establishments.markNotFound(RUN);

        assertThat(marked).isEqualTo(1);
        assertThat(exclusions())
                .containsExactly(
                        new Exclusion("12345678900012", "NOT_FOUND"),
                        new Exclusion("22345678900012", "NOT_FOUND"));
    }

    @Test
    void pageWithAnEstablishmentTheDatabaseRefusesIsNotWrittenAtAll() {
        assertThatThrownBy(
                        () ->
                                establishments.save(
                                        List.of(
                                                assess(retained().siret("12345678900012")),
                                                assess(
                                                        retained()
                                                                .siret("22345678900012")
                                                                .communeCode("99999"))),
                                        RUN))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(rows()).isZero();
    }

    @ParameterizedTest
    @CsvSource({
        "image_file, ../secret.webp",
        "image_file, bistrot.svg",
        "jobs_url, javascript:alert(1)",
        "jobs_url, http://example.invalid/emplois",
        "jobs_url, https://emploi-go.fr@example.invalid/emplois"
    })
    void ourDataRefusesUnsafeValues(String column, String value) {
        establishments.save(List.of(assess(retained())), RUN);

        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "UPDATE establishments SET "
                                                + column
                                                + " = ? WHERE siret = ?",
                                        value,
                                        SIRET))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @ParameterizedTest
    @CsvSource(
            nullValues = "NULL",
            value = {
                "00, SMALL",
                "03, SMALL",
                "11, MEDIUM",
                "31, MEDIUM",
                "32, LARGE",
                "53, LARGE",
                "NN, NULL"
            })
    void theDatabaseDerivesTheSizeFromTheHeadcountRange(String code, String size) {
        establishments.save(List.of(assess(retained().headcountRange(code))), RUN);

        assertThat(
                        jdbc.queryForObject(
                                "SELECT size FROM establishments WHERE siret = ?",
                                String.class,
                                SIRET))
                .isEqualTo(size);
    }

    private record Exclusion(String siret, String reason) {}

    private List<Exclusion> exclusions() {
        return jdbc.query(
                "SELECT siret, exclusion_reason FROM establishments ORDER BY siret",
                (row, number) -> new Exclusion(row.getString(1), row.getString(2)));
    }

    private List<String> sirets() {
        return jdbc.queryForList("SELECT siret FROM establishments ORDER BY siret", String.class);
    }

    private int rows() {
        return jdbc.queryForObject("SELECT count(*) FROM establishments", Integer.class);
    }

    private static TestDirectoryEstablishment notClassified() {
        return retained().activities(null, null);
    }

    private static TestDirectoryEstablishment withoutName() {
        return retained().signs().namedAfterPerson("JEANNE FICTIVE");
    }

    private static Assessment assess(TestDirectoryEstablishment establishment) {
        return establishment.build().assess(CLASSIFIED, Set.of());
    }
}
