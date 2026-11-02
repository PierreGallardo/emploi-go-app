package fr.emploigo.establishment.application.search;

import static org.assertj.core.api.Assertions.assertThat;

import fr.emploigo.establishment.domain.Sector;
import fr.emploigo.establishment.domain.Siret;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

class SuggestWhatUseCaseTest {
    private final FakeJobRepository jobs = new FakeJobRepository();
    private final FakePublishedEstablishmentRepository establishments =
            new FakePublishedEstablishmentRepository();
    private final SuggestWhatUseCase suggestions = new SuggestWhatUseCase(jobs, establishments);

    @Test
    void jobsAreGroupedUnderTheirSectorInTheOrderOfTheirBestJob() {
        jobs.add(new JobMatch("Boulanger", Sector.FOOD))
                .add(new JobMatch("Vendeur en boulangerie", Sector.RETAIL))
                .add(new JobMatch("Boulanger-pâtissier", Sector.FOOD));

        var found = suggestions.suggest("boulan");

        assertThat(found.jobs())
                .containsExactly(
                        new WhatSuggestions.JobGroup(
                                Sector.FOOD, List.of("Boulanger", "Boulanger-pâtissier")),
                        new WhatSuggestions.JobGroup(
                                Sector.RETAIL, List.of("Vendeur en boulangerie")));
    }

    @Test
    void establishmentsAreShownInTheCaseOfFrench() {
        establishments.add(
                new EstablishmentMatch(
                        new Siret("12345678900012"), "LE BISTROT", "SOCIETE FICTIVE", Sector.FOOD));

        var found = suggestions.suggest("bistrot");

        assertThat(found.establishments())
                .extracting(EstablishmentMatch::name)
                .containsExactly("Le Bistrot");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "b", "1 2", " é "})
    void textOfFewerThanTwoLettersSuggestsNothingWithoutAsking(String text) {
        var found = suggestions.suggest(text);

        assertThat(found.jobs()).isEmpty();
        assertThat(jobs.asked()).isEmpty();
        assertThat(establishments.named()).isEmpty();
    }
}
