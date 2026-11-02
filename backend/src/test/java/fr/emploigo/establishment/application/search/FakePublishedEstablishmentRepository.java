package fr.emploigo.establishment.application.search;

import fr.emploigo.establishment.domain.EstablishmentSort;
import fr.emploigo.establishment.domain.SearchCriteria;
import fr.emploigo.establishment.domain.Siret;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The establishments given, found whatever the criteria; records the texts suggested for and the
 * limits of the points asked for.
 */
public final class FakePublishedEstablishmentRepository
        implements PublishedEstablishmentRepository {
    private final List<EstablishmentSummary> summaries = new ArrayList<>();
    private final List<EstablishmentDetail> details = new ArrayList<>();
    private final List<EstablishmentMatch> matches = new ArrayList<>();
    private final List<String> named = new ArrayList<>();
    private final List<Integer> pointLimits = new ArrayList<>();

    public FakePublishedEstablishmentRepository add(EstablishmentSummary summary) {
        summaries.add(summary);
        return this;
    }

    public FakePublishedEstablishmentRepository add(EstablishmentDetail detail) {
        details.add(detail);
        return this;
    }

    public FakePublishedEstablishmentRepository add(EstablishmentMatch match) {
        matches.add(match);
        return this;
    }

    public List<String> named() {
        return named;
    }

    public List<Integer> pointLimits() {
        return pointLimits;
    }

    @Override
    public EstablishmentPage search(
            SearchCriteria criteria, EstablishmentSort sort, int page, int size) {
        return new EstablishmentPage(summaries, summaries.size());
    }

    @Override
    public List<EstablishmentPoint> findPoints(SearchCriteria criteria, int limit) {
        pointLimits.add(limit);
        return List.of();
    }

    @Override
    public Optional<EstablishmentDetail> findDetail(Siret siret) {
        return details.stream().filter(detail -> detail.siret().equals(siret)).findFirst();
    }

    @Override
    public List<EstablishmentMatch> findNamed(String text, int limit) {
        named.add(text);
        return matches.stream().limit(limit).toList();
    }
}
