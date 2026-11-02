package fr.emploigo.establishment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import org.junit.jupiter.api.Test;

import java.util.List;

class StreetTest {
    private static final String DIJON = "21231";
    private static final String TALANT = "21617";

    @Test
    void addressesOfOneStreetOfOneCommuneAreOneStreet() {
        List<Street> streets =
                Street.group(
                        List.of(
                                address(DIJON, "Rue Fictive", 1, null, 5.0400),
                                address(TALANT, "Rue Fictive", 1, null, 5.0000),
                                address(DIJON, "Rue Fictive", 3, null, 5.0410),
                                address(DIJON, "Avenue Fictive", 2, null, 5.0500)));

        assertThat(streets)
                .extracting(Street::communeCode, Street::name)
                .containsExactly(
                        tuple(DIJON, "Rue Fictive"),
                        tuple(TALANT, "Rue Fictive"),
                        tuple(DIJON, "Avenue Fictive"));
    }

    @Test
    void streetLiesAtItsNumberClosestToTheMiddleOfThemAll() {
        List<Street> streets =
                Street.group(
                        List.of(
                                address(DIJON, "Rue Fictive", 1, null, 5.0400),
                                address(DIJON, "Rue Fictive", 3, null, 5.0420),
                                address(DIJON, "Rue Fictive", 5, null, 5.0480)));

        assertThat(streets)
                .singleElement()
                .extracting(Street::position)
                .isEqualTo(new Position(5.0420, 47.32));
    }

    @Test
    void numbersAreInIncreasingOrderAndALabelGivenTwiceKeepsTheFirst() {
        List<Street> streets =
                Street.group(
                        List.of(
                                address(DIJON, "Rue Fictive", 12, null, 5.0400),
                                address(DIJON, "Rue Fictive", 2, "bis", 5.0410),
                                address(DIJON, "Rue Fictive", 2, null, 5.0420),
                                address(DIJON, "Rue Fictive", 12, null, 5.0430)));

        assertThat(streets.getFirst().numbers())
                .extracting(HouseNumber::label, number -> number.position().longitude())
                .containsExactly(tuple("2", 5.0420), tuple("2 bis", 5.0410), tuple("12", 5.0400));
    }

    private static Address address(
            String commune, String street, int number, String suffix, double longitude) {
        return new Address(
                commune, street, new HouseNumber(number, suffix, new Position(longitude, 47.32)));
    }
}
