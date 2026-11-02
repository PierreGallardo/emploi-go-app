package fr.emploigo.establishment.application.search;

import fr.emploigo.establishment.domain.EstablishmentSort;
import fr.emploigo.establishment.domain.SearchCriteria;
import fr.emploigo.establishment.domain.Siret;

import java.util.List;
import java.util.Optional;

/**
 * The establishments the map shows: visible and retained by the import. The others do not exist for
 * it.
 */
public interface PublishedEstablishmentRepository {
    /** One page (from 0) of the establishments that meet the criteria, in the given order. */
    EstablishmentPage search(SearchCriteria criteria, EstablishmentSort sort, int page, int size);

    /** The positioned establishments that meet the criteria, at most {@code limit}. */
    List<EstablishmentPoint> findPoints(SearchCriteria criteria, int limit);

    Optional<EstablishmentDetail> findDetail(Siret siret);

    /** The establishments whose name or company name looks like {@code text}, closest first. */
    List<EstablishmentMatch> findNamed(String text, int limit);
}
