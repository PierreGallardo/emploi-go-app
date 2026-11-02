package fr.emploigo.establishment.application.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import fr.emploigo.establishment.application.search.PlaceSuggestion.Kind;
import fr.emploigo.establishment.domain.Position;
import fr.emploigo.establishment.domain.TransitLine;
import fr.emploigo.establishment.domain.TransitMode;
import fr.emploigo.establishment.domain.TransitStop;

import org.junit.jupiter.api.Test;

import java.util.stream.Stream;

class SuggestPlacesUseCaseTest {
    private static final Position HERE = new Position(5.04, 47.32);
    private static final StreetMatch STREET = new StreetMatch(1, "Rue Fictive", "Dijon", HERE);

    private final FakePlaceRepository places = new FakePlaceRepository();
    private final SuggestPlacesUseCase suggestions = new SuggestPlacesUseCase(places);

    @Test
    void communesComeFirstThenStreetsThenStops() {
        places.add(new CommuneMatch("Fontaine-lès-Dijon", HERE))
                .add(STREET)
                .add(stop("Fontaine d'Ouche", "T1"));

        var found = suggestions.suggest("fontaine");

        assertThat(found)
                .extracting(PlaceSuggestion::kind)
                .containsExactly(Kind.COMMUNE, Kind.STREET, Kind.STOP);
    }

    @Test
    void streetTypedWithANumberGivesItsAddressesWithThatNumber() {
        places.add(STREET)
                .add(new NumberMatch(1, "6", new Position(5.041, 47.321)))
                .add(new NumberMatch(1, "6 bis", new Position(5.042, 47.322)))
                .add(new NumberMatch(1, "8", new Position(5.043, 47.323)));

        var found = suggestions.suggest("6 rue fictive");

        assertThat(found)
                .extracting(PlaceSuggestion::label, PlaceSuggestion::detail)
                .containsExactly(
                        tuple("6 Rue Fictive", "Dijon"), tuple("6 bis Rue Fictive", "Dijon"));
    }

    @Test
    void streetWithoutTheNumberTypedIsSuggestedItself() {
        places.add(STREET).add(new NumberMatch(1, "8", new Position(5.043, 47.323)));

        var found = suggestions.suggest("6 rue fictive");

        assertThat(found)
                .extracting(PlaceSuggestion::label, PlaceSuggestion::position)
                .containsExactly(tuple("Rue Fictive", HERE));
    }

    @Test
    void stopShowsItsModeAndItsFirstFourLines() {
        places.add(stop("Darcy", "T1", "T2", "10", "12", "CITY"));

        var found = suggestions.suggest("darcy");

        assertThat(found)
                .extracting(PlaceSuggestion::detail, PlaceSuggestion::mode)
                .containsExactly(tuple("10, 12, CITY, T1", TransitMode.TRAM));
    }

    private static TransitStop stop(String name, String... lines) {
        return new TransitStop(
                name,
                HERE,
                Stream.of(lines)
                        .map(
                                line ->
                                        new TransitLine(
                                                line,
                                                line.startsWith("T")
                                                        ? TransitMode.TRAM
                                                        : TransitMode.BUS))
                        .toList());
    }
}
