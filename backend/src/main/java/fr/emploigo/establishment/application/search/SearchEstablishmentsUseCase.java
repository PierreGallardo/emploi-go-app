package fr.emploigo.establishment.application.search;

import fr.emploigo.config.ApiOnly;
import fr.emploigo.establishment.domain.EstablishmentSort;
import fr.emploigo.establishment.domain.SearchCriteria;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The list of the map: one page of the published establishments that meet the criteria. */
@Service
@ApiOnly
public class SearchEstablishmentsUseCase {
    public static final int MAX_PAGE_SIZE = 50;

    private final PublishedEstablishmentRepository establishments;

    public SearchEstablishmentsUseCase(PublishedEstablishmentRepository establishments) {
        this.establishments = establishments;
    }

    /**
     * Throws {@link InvalidSearchException} for a distance from no place, or a page out of bounds.
     */
    @Transactional(readOnly = true)
    public EstablishmentPage search(
            SearchCriteria criteria, EstablishmentSort sort, int page, int size) {
        if (sort == EstablishmentSort.DISTANCE && criteria.area().isEmpty())
            throw new InvalidSearchException("Distance from no place.");
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE)
            throw new InvalidSearchException("Not a page of the list.");
        EstablishmentPage found = establishments.search(criteria, sort, page, size);
        return new EstablishmentPage(
                found.items().stream().map(EstablishmentSummary::shown).toList(), found.total());
    }
}
