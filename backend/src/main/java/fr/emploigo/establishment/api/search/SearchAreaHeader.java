package fr.emploigo.establishment.api.search;

import fr.emploigo.establishment.application.search.InvalidSearchException;
import fr.emploigo.establishment.domain.Position;
import fr.emploigo.establishment.domain.SearchArea;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The place a youth searches around travels in a header, never in the URL: proxies and diagnostics
 * keep URLs, and the position of a youth is neither stored nor logged.
 */
final class SearchAreaHeader {
    static final String NAME = "Search-Area";
    static final String DESCRIPTION =
            "Cercle de recherche « longitude,latitude,rayon en km » (WGS 84, rayon de 1 à 6) :"
                    + " la position ne passe jamais dans l'URL";
    private static final Pattern FORMAT =
            Pattern.compile("(-?\\d{1,3}(?:\\.\\d{1,10})?),(-?\\d{1,2}(?:\\.\\d{1,10})?),(\\d)");

    private SearchAreaHeader() {}

    /** The area of the header; throws {@link InvalidSearchException} for an unreadable one. */
    static Optional<SearchArea> parse(String header) {
        if (header == null) return Optional.empty();
        Matcher area = FORMAT.matcher(header.strip());
        if (!area.matches()) throw new InvalidSearchException("Unreadable search area.");
        int radius = Integer.parseInt(area.group(3));
        Optional<Position> centre = Position.parse(area.group(1), area.group(2));
        if (centre.isEmpty()
                || radius < SearchArea.MIN_RADIUS_KM
                || radius > SearchArea.MAX_RADIUS_KM)
            throw new InvalidSearchException("Search area out of bounds.");
        return Optional.of(new SearchArea(centre.get(), radius));
    }
}
