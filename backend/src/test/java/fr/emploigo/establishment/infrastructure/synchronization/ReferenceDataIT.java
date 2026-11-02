package fr.emploigo.establishment.infrastructure.synchronization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import fr.emploigo.establishment.application.synchronization.ActivityRepository;
import fr.emploigo.establishment.application.synchronization.CommuneRepository;
import fr.emploigo.establishment.domain.ActivityCode;
import fr.emploigo.establishment.domain.Nomenclature;
import fr.emploigo.support.WorkerTest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/** Our classification of the NAF and the correspondence between jobs and activities. */
@WorkerTest
class ReferenceDataIT {
    @Autowired ActivityRepository activities;
    @Autowired CommuneRepository communes;
    @Autowired JdbcTemplate jdbc;

    @ParameterizedTest
    @CsvSource({
        "NAF_REV2, 56.10A, HOSPITALITY_TOURISM",
        "NAF_REV2, 10.71C, FOOD",
        "NAF_REV2, 10.71A, INDUSTRY",
        "NAF_REV2, 47.30Z, AUTOMOTIVE",
        "NAF_REV2, 96.03Z, SOCIAL_PERSONAL_SERVICES",
        "NAF_2025, 10.71H, FOOD",
        "NAF_2025, 47.81Y, AUTOMOTIVE",
        "NAF_2025, 95.31G, AUTOMOTIVE",
        "NAF_2025, 96.21G, BEAUTY_WELLNESS"
    })
    void subClassHasTheSectorOfItsDivisionOrOfItsException(
            String nomenclature, String code, String sector) {
        assertThat(sectorOf(nomenclature, code)).isEqualTo(sector);
    }

    @ParameterizedTest
    @CsvSource({"NAF_REV2, 64.20Z", "NAF_2025, 64.21Y", "NAF_REV2, 97.00Z", "NAF_2025, 99.00Y"})
    void holdingsAndHouseholdsHaveNoSector(String nomenclature, String code) {
        assertThat(sectorOf(nomenclature, code)).isNull();
    }

    @Test
    void allowListOfTheImportHoldsTheSubClassesThatHaveASector() {
        assertThat(activities.findClassified())
                .contains(new ActivityCode(Nomenclature.NAF_2025, "56.11G"))
                .doesNotContain(new ActivityCode(Nomenclature.NAF_2025, "64.21Y"));
    }

    @Test
    void importCoversTheTwentyThreeCommunesOfDijonMetropole() {
        assertThat(communes.findCodes()).hasSize(23).startsWith("21003").contains("21231");
    }

    @Test
    void refinedJobIsLinkedToItsSubClassesInBothNomenclatures() {
        assertThat(activitiesOf("D1102"))
                .containsExactly(
                        "NAF_2025 10.71H", "NAF_2025 47.24Y", "NAF_REV2 10.71C", "NAF_REV2 47.24Z");
    }

    @Test
    void jobOfADivisionWithExceptionsTakesOnlyItsPartWithoutException() {
        assertThat(activitiesOf("D1507"))
                .contains("NAF_REV2 47.11F", "NAF_2025 47.11L")
                .doesNotContain("NAF_REV2 47.24Z", "NAF_REV2 47.30Z", "NAF_2025 47.24Y");
    }

    @Test
    void jobOfADivisionWithoutExceptionTakesTheWholeDivision() {
        assertThat(activitiesOf("G1803"))
                .filteredOn(activity -> activity.startsWith("NAF_REV2"))
                .containsExactly(
                        "NAF_REV2 56.10A",
                        "NAF_REV2 56.10B",
                        "NAF_REV2 56.10C",
                        "NAF_REV2 56.21Z",
                        "NAF_REV2 56.29A",
                        "NAF_REV2 56.29B",
                        "NAF_REV2 56.30Z");
    }

    @Test
    void jobFollowsItsActivitiesIntoTheDivisionsOfNaf2025() {
        assertThat(activitiesOf("I1604"))
                .filteredOn(activity -> activity.startsWith("NAF_2025"))
                .contains("NAF_2025 47.81Y", "NAF_2025 95.31G")
                .doesNotContain("NAF_2025 46.18Y");
    }

    @Test
    void migrationsLeaveTheCorrespondenceBetweenJobsAndActivitiesUpToDate() {
        String definition =
                jdbc.queryForObject(
                        """
                        SELECT btrim(pg_get_viewdef('reference.job_activities'::regclass), E' ;\\n')
                        """,
                        String.class);

        assertThat(
                        jdbc.queryForObject(
                                """
                                SELECT count(*)
                                FROM (((%1$s) EXCEPT TABLE reference.job_activities)
                                    UNION ALL (TABLE reference.job_activities EXCEPT (%1$s))) stale
                                """
                                        .formatted(definition),
                                Long.class))
                .isZero();
    }

    @Test
    void theDatabaseRefusesAJobLinkedToAnUnknownDivision() {
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "INSERT INTO reference.job_divisions (rome_code, division)"
                                                + " VALUES ('D1102', '00')"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("job_divisions_division_nomenclature_division_fkey");
    }

    private String sectorOf(String nomenclature, String code) {
        return jdbc.queryForObject(
                """
                SELECT sector_code
                FROM reference.naf_activities
                WHERE nomenclature = ? AND code = ?
                """,
                String.class,
                nomenclature,
                code);
    }

    private List<String> activitiesOf(String romeCode) {
        return jdbc.queryForList(
                """
                SELECT nomenclature || ' ' || activity_code
                FROM reference.job_activities
                WHERE rome_code = ?
                ORDER BY 1
                """,
                String.class,
                romeCode);
    }
}
