package fr.emploigo.establishment.application.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import fr.emploigo.establishment.domain.EstablishmentSort;
import fr.emploigo.establishment.domain.Position;
import fr.emploigo.establishment.domain.SearchArea;
import fr.emploigo.establishment.domain.SearchCriteria;
import fr.emploigo.establishment.domain.Sector;
import fr.emploigo.establishment.domain.Siret;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Optional;
import java.util.Set;

class SearchEstablishmentsUseCaseTest {
    private static final SearchCriteria EVERYWHERE = criteria(Optional.empty());

    private final FakePublishedEstablishmentRepository establishments =
            new FakePublishedEstablishmentRepository();
    private final SearchEstablishmentsUseCase search =
            new SearchEstablishmentsUseCase(establishments);

    @Test
    void namesOfTheListAreShownInTheCaseOfFrench() {
        establishments.add(summary("LE BISTROT FICTIF", "SOCIETE FICTIVE"));

        var page = search.search(EVERYWHERE, EstablishmentSort.NAME, 0, 20);

        assertThat(page.items())
                .extracting(EstablishmentSummary::name)
                .containsExactly("Le Bistrot Fictif");
    }

    @Test
    void sortByDistanceFromNoPlaceIsRefused() {
        assertThatThrownBy(() -> search.search(EVERYWHERE, EstablishmentSort.DISTANCE, 0, 20))
                .isInstanceOf(InvalidSearchException.class);
    }

    @ParameterizedTest
    @CsvSource({"-1, 20", "0, 0", "0, 51"})
    void pageOutOfBoundsIsRefused(int page, int size) {
        assertThatThrownBy(() -> search.search(EVERYWHERE, EstablishmentSort.NAME, page, size))
                .isInstanceOf(InvalidSearchException.class);
    }

    private static SearchCriteria criteria(Optional<SearchArea> area) {
        return new SearchCriteria(
                "", Optional.empty(), Set.of(), Set.of(), Set.of(), false, area, Set.of());
    }

    static EstablishmentSummary summary(String name, String companyName) {
        return new EstablishmentSummary(
                new Siret("12345678900012"),
                name,
                companyName,
                Sector.HOSPITALITY_TOURISM,
                "Dijon",
                new Position(5.04, 47.32),
                null,
                null,
                List.of("Serveur"),
                null);
    }
}
