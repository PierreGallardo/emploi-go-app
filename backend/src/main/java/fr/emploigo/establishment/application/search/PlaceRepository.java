package fr.emploigo.establishment.application.search;

import fr.emploigo.establishment.domain.TransitStop;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * The places of « Où ? ». The words match without accent nor case, each anywhere in the name; a
 * name that starts with the first word comes first, then one with a word that starts with it.
 */
public interface PlaceRepository {
    List<CommuneMatch> findCommunes(String words, int limit);

    /** The streets whose name, with their commune, has every word. */
    List<StreetMatch> findStreets(String words, int limit);

    /** The numbers of these streets that are {@code number}, with this repetition index if any. */
    List<NumberMatch> findNumbers(
            Collection<Integer> streetIds, int number, Optional<String> suffix);

    List<TransitStop> findStops(String words, int limit);
}
