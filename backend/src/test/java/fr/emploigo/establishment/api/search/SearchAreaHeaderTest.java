package fr.emploigo.establishment.api.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import fr.emploigo.establishment.application.search.InvalidSearchException;
import fr.emploigo.establishment.domain.Position;
import fr.emploigo.establishment.domain.SearchArea;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SearchAreaHeaderTest {
    @Test
    void longitudeLatitudeAndRadiusAreAnArea() {
        assertThat(SearchAreaHeader.parse("5.0415,47.3220,5"))
                .contains(new SearchArea(new Position(5.0415, 47.322), 5));
    }

    @Test
    void absentHeaderIsNoArea() {
        assertThat(SearchAreaHeader.parse(null)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "5.04,47.32",
                "5.04;47.32;5",
                "5.04,47.32,0",
                "5.04,47.32,7",
                "200,47,5"
            })
    void otherValueIsRefused(String header) {
        assertThatThrownBy(() -> SearchAreaHeader.parse(header))
                .isInstanceOf(InvalidSearchException.class);
    }
}
