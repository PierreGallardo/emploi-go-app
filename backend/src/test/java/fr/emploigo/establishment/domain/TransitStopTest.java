package fr.emploigo.establishment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

class TransitStopTest {
    private static final TransitLine T1 = new TransitLine("T1", TransitMode.TRAM);
    private static final TransitLine L5 = new TransitLine("L5", TransitMode.BUS);
    private static final TransitLine BUS_10 = new TransitLine("10", TransitMode.BUS);
    private static final Position DARCY = new Position(5.0346, 47.3229);

    /** About 75 meters east of {@link #DARCY}. */
    private static final Position DARCY_EAST = new Position(5.0356, 47.3229);

    @Test
    void platformsOfTheSameNameCloseTogetherAreOneStopInTheirMiddleWithAllTheirLines() {
        List<TransitStop> stops =
                TransitStop.group(
                        List.of(
                                new TransitPlatform("Darcy", DARCY, Set.of(T1, L5)),
                                new TransitPlatform("Darcy", DARCY_EAST, Set.of(T1, BUS_10))));

        assertThat(stops)
                .singleElement()
                .satisfies(
                        stop -> {
                            assertThat(stop.name()).isEqualTo("Darcy");
                            assertThat(stop.position().longitude()).isCloseTo(5.0351, within(1e-9));
                            assertThat(stop.position().latitude()).isCloseTo(47.3229, within(1e-9));
                            assertThat(stop.lines()).containsExactly(BUS_10, L5, T1);
                        });
    }

    @Test
    void platformsOfTheSameNameFarApartAreTwoStops() {
        List<TransitStop> stops =
                TransitStop.group(
                        List.of(
                                new TransitPlatform("Mairie", DARCY, Set.of(L5)),
                                new TransitPlatform(
                                        "Mairie", new Position(5.0400, 47.3229), Set.of(L5))));

        assertThat(stops).hasSize(2);
    }

    @Test
    void platformsCloseTogetherWithOtherNamesAreTwoStops() {
        List<TransitStop> stops =
                TransitStop.group(
                        List.of(
                                new TransitPlatform("Darcy", DARCY, Set.of(T1)),
                                new TransitPlatform("Darcy Square", DARCY_EAST, Set.of(L5))));

        assertThat(stops).extracting(TransitStop::name).containsExactly("Darcy", "Darcy Square");
    }

    @Test
    void platformCloseToAnyPlatformOfAStopJoinsIt() {
        List<TransitStop> stops =
                TransitStop.group(
                        List.of(
                                new TransitPlatform("Darcy", DARCY, Set.of(T1)),
                                new TransitPlatform("Darcy", DARCY_EAST, Set.of(T1)),
                                new TransitPlatform(
                                        "Darcy", new Position(5.0371, 47.3229), Set.of(L5))));

        assertThat(stops).singleElement().extracting(TransitStop::lines).isEqualTo(List.of(L5, T1));
    }
}
