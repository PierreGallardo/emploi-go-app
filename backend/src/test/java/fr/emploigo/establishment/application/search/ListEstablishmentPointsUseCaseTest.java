package fr.emploigo.establishment.application.search;

import static org.assertj.core.api.Assertions.assertThat;

import fr.emploigo.establishment.domain.SearchCriteria;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;

class ListEstablishmentPointsUseCaseTest {
    private final FakePublishedEstablishmentRepository establishments =
            new FakePublishedEstablishmentRepository();

    @Test
    void pointsAreReadUpToTheCapOfTheMap() {
        new ListEstablishmentPointsUseCase(establishments)
                .list(
                        new SearchCriteria(
                                "",
                                Optional.empty(),
                                Set.of(),
                                Set.of(),
                                Set.of(),
                                false,
                                Optional.empty(),
                                Set.of()));

        assertThat(establishments.pointLimits())
                .containsExactly(ListEstablishmentPointsUseCase.MAX_POINTS);
    }
}
