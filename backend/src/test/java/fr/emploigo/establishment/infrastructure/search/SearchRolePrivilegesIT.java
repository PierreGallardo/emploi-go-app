package fr.emploigo.establishment.infrastructure.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import fr.emploigo.establishment.application.search.StreetMatch;
import fr.emploigo.establishment.domain.EstablishmentSort;
import fr.emploigo.establishment.domain.Position;
import fr.emploigo.establishment.domain.SearchArea;
import fr.emploigo.establishment.domain.SearchCriteria;
import fr.emploigo.establishment.domain.Siret;
import fr.emploigo.establishment.infrastructure.MapRoles;
import fr.emploigo.support.IntegrationTest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Optional;
import java.util.Set;

/** Every read of the map runs with exactly the rights db/grants/map.sql gives the API. */
class SearchRolePrivilegesIT extends IntegrationTest {
    private static final String SIRET = "90000000100012";

    private MapRoles roles;

    @BeforeEach
    void rolesWithTheRightsOfTheScriptAndAMap() {
        roles = new MapRoles(jdbc);
        roles.create();
        jdbc.update(
                """
                INSERT INTO establishments (
                    siret, name, company_name, address, commune_code, longitude, latitude,
                    activity_nomenclature, activity_code, company_type, ess, seen_at, visible)
                VALUES (?, 'LE FOURNIL FICTIF', 'SOCIETE FICTIVE', '1 RUE FICTIVE 21000 DIJON',
                        '21231', 5.0273, 47.3233, 'NAF_REV2', '10.71C', 'COMPANY', FALSE, now(),
                        TRUE)
                """,
                SIRET);
        jdbc.update("INSERT INTO transit_lines (name, mode) VALUES ('T1', 'TRAM')");
        jdbc.update(
                """
                WITH stop AS (
                    INSERT INTO transit_stops (name, mode, longitude, latitude)
                    VALUES ('Gare Fictive', 'TRAM', 5.0270, 47.3235)
                    RETURNING id)
                INSERT INTO transit_stop_lines (stop_id, line_name) SELECT id, 'T1' FROM stop
                """);
        jdbc.update(
                """
                WITH street AS (
                    INSERT INTO address_streets (commune_code, name, longitude, latitude)
                    VALUES ('21231', 'Rue Fictive', 5.0350, 47.3220)
                    RETURNING id)
                INSERT INTO address_numbers (street_id, label, number, longitude, latitude)
                SELECT id, '6', 6, 5.0351, 47.3221 FROM street
                """);
    }

    @AfterEach
    void dropTheRoles() {
        roles.drop();
    }

    @Test
    void searchPointsPageAndSuggestionsReadWithTheRightsOfTheApi() {
        MapRoles.Jpa jpa = roles.jpa(MapRoles.API, getClass().getPackageName());
        var establishments = new JpaPublishedEstablishmentRepository(jpa.entities());
        var jobs = new JpaJobRepository(jpa.entities());
        var around =
                new SearchCriteria(
                        "fournil",
                        Optional.of("Boulanger / Boulangère"),
                        Set.of(),
                        Set.of(),
                        Set.of(),
                        true,
                        Optional.of(new SearchArea(new Position(5.03, 47.32), 2)),
                        Set.of());

        var found =
                jpa.transactions()
                        .execute(
                                status ->
                                        new Object[] {
                                            establishments
                                                    .search(
                                                            around,
                                                            EstablishmentSort.DISTANCE,
                                                            0,
                                                            20)
                                                    .total(),
                                            establishments.findPoints(around, 10).size(),
                                            establishments
                                                    .findDetail(new Siret(SIRET))
                                                    .orElseThrow()
                                                    .nearestStop()
                                                    .name(),
                                            establishments.findNamed("fournil", 5).size(),
                                            jobs.findPublished("boulanger", 8).isEmpty()
                                        });

        assertThat(found).containsExactly(1L, 1, "Gare Fictive", 1, false);
    }

    @Test
    void placesAndOpenDataReadWithTheRightsOfTheApi() {
        MapRoles.Jpa jpa = roles.jpa(MapRoles.API, getClass().getPackageName());
        var places = new JpaPlaceRepository(jpa.entities());
        var datasets = new JpaDatasetsRepository(jpa.entities());

        var found =
                jpa.transactions()
                        .execute(
                                status ->
                                        new Object[] {
                                            places.findCommunes("talant", 5).size(),
                                            places.findNumbers(
                                                            places
                                                                    .findStreets("fictive", 5)
                                                                    .stream()
                                                                    .map(StreetMatch::id)
                                                                    .toList(),
                                                            6,
                                                            Optional.empty())
                                                    .size(),
                                            places.findStops("gare", 5).size(),
                                            datasets.read().stops().size()
                                        });

        assertThat(found).containsExactly(1, 1, 1, 1);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "UPDATE establishments SET visible = FALSE",
                "DELETE FROM transit_stops",
                "INSERT INTO dataset_publications VALUES ('ADDRESSES', now(), now())",
                "DELETE FROM reference.jobs"
            })
    void apiWritesNoneOfTheMap(String change) {
        var api = new JdbcTemplate(MapRoles.connect(MapRoles.API));

        assertThatThrownBy(() -> api.update(change)).isInstanceOf(DataAccessException.class);
    }
}
