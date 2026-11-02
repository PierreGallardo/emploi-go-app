package fr.emploigo.establishment.application.search;

import fr.emploigo.config.ApiOnly;
import fr.emploigo.establishment.domain.SearchCriteria;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** The points of the map for the criteria of the list. */
@Service
@ApiOnly
public class ListEstablishmentPointsUseCase {
    /** More than the employers of the whole metropolis. */
    static final int MAX_POINTS = 20_000;

    private final PublishedEstablishmentRepository establishments;

    public ListEstablishmentPointsUseCase(PublishedEstablishmentRepository establishments) {
        this.establishments = establishments;
    }

    @Transactional(readOnly = true)
    public List<EstablishmentPoint> list(SearchCriteria criteria) {
        return establishments.findPoints(criteria, MAX_POINTS);
    }
}
