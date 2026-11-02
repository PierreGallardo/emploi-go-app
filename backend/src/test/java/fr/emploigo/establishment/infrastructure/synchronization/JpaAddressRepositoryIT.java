package fr.emploigo.establishment.infrastructure.synchronization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

import fr.emploigo.establishment.application.synchronization.AddressRepository;
import fr.emploigo.establishment.domain.HouseNumber;
import fr.emploigo.establishment.domain.Position;
import fr.emploigo.establishment.domain.Street;
import fr.emploigo.support.QueryPlans;
import fr.emploigo.support.SqlFailure;
import fr.emploigo.support.TestDatabase;
import fr.emploigo.support.WorkerTest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;

/** The replacement of the addresses on PostgreSQL, and the search form of their names. */
@WorkerTest
class JpaAddressRepositoryIT {
    private static final Instant PUBLISHED = Instant.parse("2026-10-23T05:01:54Z");
    private static final Instant REPUBLISHED = Instant.parse("2026-10-30T05:00:00Z");
    private static final Instant IMPORTED = Instant.parse("2026-10-25T03:10:00Z");

    @Autowired AddressRepository addresses;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void emptyTheTables() {
        TestDatabase.reset(jdbc);
    }

    @Test
    void streetsAreWrittenWithTheirNumbersAndThePublicationOfTheFile() {
        addresses.replace(List.of(street("Rue Fictive", 1, 2)), PUBLISHED, IMPORTED);

        assertThat(
                        jdbc.queryForList(
                                """
                                SELECT s.commune_code, s.name, n.label, n.number
                                FROM address_streets s
                                JOIN address_numbers n ON n.street_id = s.id
                                ORDER BY n.number, n.label
                                """))
                .extracting(
                        row -> row.get("commune_code"),
                        row -> row.get("name"),
                        row -> row.get("label"),
                        row -> row.get("number"))
                .containsExactly(
                        tuple("21231", "Rue Fictive", "1", 1),
                        tuple("21231", "Rue Fictive", "2 bis", 2));
        assertThat(addresses.findPublishedAt()).contains(PUBLISHED);
    }

    @Test
    void newFileReplacesEveryStreetAndNumber() {
        addresses.replace(List.of(street("Rue Fictive", 1, 2)), PUBLISHED, IMPORTED);

        addresses.replace(List.of(street("Avenue Fictive", 5, 7)), REPUBLISHED, IMPORTED);

        assertThat(jdbc.queryForList("SELECT name FROM address_streets", String.class))
                .containsExactly("Avenue Fictive");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM address_numbers", Integer.class))
                .isEqualTo(2);
        assertThat(addresses.findPublishedAt()).contains(REPUBLISHED);
    }

    @Test
    void streetsBeyondOneFlushAreAllWritten() {
        addresses.replace(
                IntStream.rangeClosed(1, 201)
                        .mapToObj(n -> street("Rue Fictive " + n, 1, 2))
                        .toList(),
                PUBLISHED,
                IMPORTED);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM address_streets", Integer.class))
                .isEqualTo(201);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM address_numbers", Integer.class))
                .isEqualTo(402);
    }

    @Test
    void failedReplacementKeepsTheStreetsAndThePublicationInPlace() {
        addresses.replace(List.of(street("Rue Fictive", 1, 2)), PUBLISHED, IMPORTED);

        try (var failure = SqlFailure.inject(jdbc, "address_numbers", "BEFORE INSERT")) {
            assertThatThrownBy(
                            () ->
                                    addresses.replace(
                                            List.of(street("Avenue Fictive", 5, 7)),
                                            REPUBLISHED,
                                            IMPORTED))
                    .isInstanceOf(DataAccessException.class);
        }

        assertThat(jdbc.queryForList("SELECT name FROM address_streets", String.class))
                .containsExactly("Rue Fictive");
        assertThat(addresses.findPublishedAt()).contains(PUBLISHED);
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "Rue de l'Église-Saint-Michel | rue de l eglise saint michel",
                "  BOULEVARD   Maréchal Joffre | boulevard marechal joffre",
                "Cœur de Ville | coeur de ville"
            })
    void searchFormIsLowerCaseWithoutAccentsWithOneSpaceBetweenWords(String text, String form) {
        assertThat(jdbc.queryForObject("SELECT search_text(?)", String.class, text))
                .isEqualTo(form);
    }

    @Test
    void searchOnTheNamesOfTheStreetsUsesTheirIndex() {
        String plan =
                QueryPlans.of(
                        jdbc,
                        """
                        SELECT id FROM address_streets
                        WHERE search_text(name) LIKE '%' || search_text(?) || '%'
                        """,
                        List.of("Liberté"));

        assertThat(plan).contains("address_streets_name_idx");
    }

    private static Street street(String name, int first, int second) {
        return new Street(
                "21231",
                name,
                new Position(5.04, 47.32),
                List.of(
                        new HouseNumber(first, null, new Position(5.04, 47.32)),
                        new HouseNumber(second, "bis", new Position(5.0401, 47.3201))));
    }
}
