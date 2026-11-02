package fr.emploigo.establishment.infrastructure.search;

import static org.assertj.core.api.Assertions.assertThat;

import fr.emploigo.establishment.application.search.PublishedEstablishmentRepository;
import fr.emploigo.establishment.domain.SearchCriteria;
import fr.emploigo.support.IntegrationTest;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Optional;
import java.util.Set;

/** The reads of the map that its API cannot show on PostgreSQL. */
class JpaPublishedEstablishmentRepositoryIT extends IntegrationTest {
    @Autowired PublishedEstablishmentRepository establishments;

    @Test
    void pointsStopAtTheLimitAskedFor() {
        jdbc.update(
                """
                INSERT INTO establishments (
                    siret, name, company_name, address, commune_code, longitude, latitude,
                    activity_nomenclature, activity_code, company_type, ess, seen_at, visible)
                SELECT siret, 'FICTIF', 'SOCIETE FICTIVE', '1 RUE FICTIVE 21000 DIJON', '21231',
                       5.04, 47.32, 'NAF_REV2', '10.71C', 'COMPANY', FALSE, now(), TRUE
                FROM (VALUES ('90000000100012'), ('90000000200012'), ('90000000300012'))
                    AS fictitious (siret)
                """);

        var points =
                establishments.findPoints(
                        new SearchCriteria(
                                "",
                                Optional.empty(),
                                Set.of(),
                                Set.of(),
                                Set.of(),
                                false,
                                Optional.empty(),
                                Set.of()),
                        2);

        assertThat(points).hasSize(2);
    }
}
