package fr.emploigo.establishment.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;

class PlaceQueryTest {
    @Test
    void textStartingWithANumberAndAnIndexIsAnAddress() {
        assertThat(PlaceQuery.of("  6 BIS avenue Foch "))
                .contains(new PlaceQuery(Optional.of(6), Optional.of("bis"), "avenue foch"));
    }

    @Test
    void textWithoutNumberIsWordsOnly() {
        assertThat(PlaceQuery.of("Rue de la Liberté"))
                .contains(new PlaceQuery(Optional.empty(), Optional.empty(), "rue de la liberté"));
    }

    @Test
    void letterGluedToTheNumberIsItsIndex() {
        assertThat(PlaceQuery.of("12b rue Fictive"))
                .contains(new PlaceQuery(Optional.of(12), Optional.of("b"), "rue fictive"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "a", "12", "12 b", "  "})
    void textWithFewerThanTwoLettersIsNoQuery(String text) {
        assertThat(PlaceQuery.of(text)).isEmpty();
    }
}
