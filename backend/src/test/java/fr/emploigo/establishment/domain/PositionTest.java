package fr.emploigo.establishment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PositionTest {
    @Test
    void decimalNumbersAreAPosition() {
        assertThat(Position.parse("5.041479", "47.322047"))
                .contains(new Position(5.041479, 47.322047));
    }

    @ParameterizedTest
    @CsvSource(
            nullValues = "null",
            value = {
                "'', 47.32",
                "5.04, abc",
                "5.04, null",
                "181, 47.32",
                "5.04, -91",
                "NaN, 47.32"
            })
    void otherValuesAreNoPosition(String longitude, String latitude) {
        assertThat(Position.parse(longitude, latitude)).isEmpty();
    }

    // Expected values given by PostGIS: ST_Transform(ST_SetSRID(ST_MakePoint(x, y), 2154), 4326).
    @ParameterizedTest
    @CsvSource({
        "652469, 6862035, 2.3521997, 48.8565977",
        "652469.0, 6862035.00, 2.3521997, 48.8565977",
        "856000, 6692000, 5.0650801, 47.3099618",
        "700000, 6600000, 3.0, 46.5"
    })
    void lambert93CoordinatesAreConvertedToDegrees(
            String east, String north, double longitude, double latitude) {
        Position position = Position.parseLambert93(east, north).orElseThrow();

        assertThat(position.longitude()).isCloseTo(longitude, within(1e-6));
        assertThat(position.latitude()).isCloseTo(latitude, within(1e-6));
    }

    @ParameterizedTest
    @CsvSource(
            nullValues = "null",
            value = {"'', 6862035", "652469, abc", "652469, null", "null, 6862035", "0, 0"})
    void otherLambert93ValuesAreNoPosition(String east, String north) {
        assertThat(Position.parseLambert93(east, north)).isEmpty();
    }

    @Test
    void distanceIsInMeters() {
        // 588 meters east and 133 meters north at the latitude of Dijon.
        double meters = new Position(5.0346, 47.3229).metersTo(new Position(5.0424, 47.3241));

        assertThat(meters).isCloseTo(603, within(1.0));
    }
}
