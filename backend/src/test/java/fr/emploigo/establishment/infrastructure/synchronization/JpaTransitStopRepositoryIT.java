package fr.emploigo.establishment.infrastructure.synchronization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import fr.emploigo.establishment.application.synchronization.TransitStopRepository;
import fr.emploigo.establishment.domain.Position;
import fr.emploigo.establishment.domain.TransitLine;
import fr.emploigo.establishment.domain.TransitMode;
import fr.emploigo.establishment.domain.TransitStop;
import fr.emploigo.support.SqlFailure;
import fr.emploigo.support.TestDatabase;
import fr.emploigo.support.WorkerTest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** The replacement of the Divia stops on PostgreSQL, with PostGIS. */
@WorkerTest
class JpaTransitStopRepositoryIT {
    private static final Instant PUBLISHED = Instant.parse("2026-10-23T09:34:39Z");
    private static final Instant REPUBLISHED = Instant.parse("2026-10-30T09:00:00Z");
    private static final Instant IMPORTED = Instant.parse("2026-10-25T03:00:00.123456Z");
    private static final TransitLine T1 = new TransitLine("T1", TransitMode.TRAM);
    private static final TransitLine L5 = new TransitLine("L5", TransitMode.BUS);

    @Autowired TransitStopRepository stops;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void emptyTheTables() {
        TestDatabase.reset(jdbc);
    }

    @Test
    void stopsAreWrittenWithTheirLinesTheirLocationAndThePublicationOfTheFeed() {
        stops.replace(
                List.of(new TransitStop("Darcy", new Position(5.0346, 47.3229), List.of(T1, L5))),
                PUBLISHED,
                IMPORTED);

        assertThat(
                        jdbc.queryForMap(
                                """
                                SELECT s.name, s.mode, ST_X(s.location::geometry) AS x,
                                       ST_Y(s.location::geometry) AS y,
                                       string_agg(l.name || ' ' || l.mode, ', '
                                                  ORDER BY l.name) AS lines
                                FROM transit_stops s
                                JOIN transit_stop_lines sl ON sl.stop_id = s.id
                                JOIN transit_lines l ON l.name = sl.line_name
                                GROUP BY s.id
                                """))
                .isEqualTo(
                        Map.of(
                                "name",
                                "Darcy",
                                "mode",
                                "TRAM",
                                "x",
                                5.0346,
                                "y",
                                47.3229,
                                "lines",
                                "L5 BUS, T1 TRAM"));
        assertThat(stops.findPublishedAt()).contains(PUBLISHED);
    }

    @Test
    void newFeedReplacesEveryStopAndLine() {
        stops.replace(
                List.of(new TransitStop("Darcy", new Position(5.0346, 47.3229), List.of(T1, L5))),
                PUBLISHED,
                IMPORTED);

        stops.replace(
                List.of(new TransitStop("Godrans", new Position(5.0424, 47.3241), List.of(T1))),
                REPUBLISHED,
                IMPORTED);

        assertThat(jdbc.queryForList("SELECT name FROM transit_stops", String.class))
                .containsExactly("Godrans");
        assertThat(jdbc.queryForList("SELECT name FROM transit_lines", String.class))
                .containsExactly("T1");
        assertThat(stops.findPublishedAt()).contains(REPUBLISHED);
    }

    @Test
    void lineOfSeveralStopsIsWrittenOnce() {
        stops.replace(
                List.of(
                        new TransitStop("Darcy", new Position(5.0346, 47.3229), List.of(T1)),
                        new TransitStop("Godrans", new Position(5.0424, 47.3241), List.of(T1))),
                PUBLISHED,
                IMPORTED);

        assertThat(jdbc.queryForList("SELECT name FROM transit_lines", String.class))
                .containsExactly("T1");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transit_stop_lines", Integer.class))
                .isEqualTo(2);
    }

    @Test
    void failedReplacementKeepsTheStopsAndThePublicationInPlace() {
        stops.replace(
                List.of(new TransitStop("Darcy", new Position(5.0346, 47.3229), List.of(T1))),
                PUBLISHED,
                IMPORTED);

        try (var failure = SqlFailure.inject(jdbc, "transit_stops", "BEFORE INSERT")) {
            assertThatThrownBy(
                            () ->
                                    stops.replace(
                                            List.of(
                                                    new TransitStop(
                                                            "Godrans",
                                                            new Position(5.0424, 47.3241),
                                                            List.of(L5))),
                                            REPUBLISHED,
                                            IMPORTED))
                    .isInstanceOf(DataAccessException.class);
        }

        assertThat(jdbc.queryForList("SELECT name FROM transit_stops", String.class))
                .containsExactly("Darcy");
        assertThat(stops.findPublishedAt()).contains(PUBLISHED);
    }

    @Test
    void noPublicationIsKnownBeforeTheFirstImport() {
        assertThat(stops.findPublishedAt()).isEmpty();
    }
}
