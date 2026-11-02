package fr.emploigo.establishment.application.search;

import fr.emploigo.config.ApiOnly;
import fr.emploigo.establishment.application.search.PlaceSuggestion.Kind;
import fr.emploigo.establishment.domain.PlaceQuery;
import fr.emploigo.establishment.domain.TransitLine;
import fr.emploigo.establishment.domain.TransitStop;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The suggestions of « Où ? »: communes, then streets, then Divia stops. A street typed with a
 * number gives its addresses with that number, or the street when it has none.
 */
@Service
@ApiOnly
public class SuggestPlacesUseCase {
    static final int MAX_COMMUNES = 3;
    static final int MAX_STREETS = 5;
    static final int MAX_NUMBERS_PER_STREET = 2;
    static final int MAX_STOPS = 5;
    static final int MAX_LINES_SHOWN = 4;

    private final PlaceRepository places;

    public SuggestPlacesUseCase(PlaceRepository places) {
        this.places = places;
    }

    @Transactional(readOnly = true)
    public List<PlaceSuggestion> suggest(String text) {
        return PlaceQuery.of(text).map(this::suggest).orElse(List.of());
    }

    private List<PlaceSuggestion> suggest(PlaceQuery query) {
        List<PlaceSuggestion> suggestions = new ArrayList<>();
        places.findCommunes(query.words(), MAX_COMMUNES)
                .forEach(
                        commune ->
                                suggestions.add(
                                        new PlaceSuggestion(
                                                Kind.COMMUNE,
                                                commune.name(),
                                                null,
                                                null,
                                                commune.centre())));
        suggestions.addAll(streets(query));
        places.findStops(query.words(), MAX_STOPS).forEach(stop -> suggestions.add(stop(stop)));
        return suggestions;
    }

    private List<PlaceSuggestion> streets(PlaceQuery query) {
        List<StreetMatch> streets = places.findStreets(query.words(), MAX_STREETS);
        List<PlaceSuggestion> addresses = new ArrayList<>();
        if (query.number().isPresent() && !streets.isEmpty()) {
            Map<Integer, StreetMatch> byId = new LinkedHashMap<>();
            streets.forEach(street -> byId.put(street.id(), street));
            Map<Integer, List<NumberMatch>> numbers =
                    places.findNumbers(byId.keySet(), query.number().get(), query.suffix()).stream()
                            .collect(Collectors.groupingBy(NumberMatch::streetId));
            for (StreetMatch street : streets)
                numbers.getOrDefault(street.id(), List.of()).stream()
                        .limit(MAX_NUMBERS_PER_STREET)
                        .forEach(
                                number ->
                                        addresses.add(
                                                new PlaceSuggestion(
                                                        Kind.STREET,
                                                        number.label() + " " + street.name(),
                                                        street.commune(),
                                                        null,
                                                        number.position())));
        }
        if (!addresses.isEmpty()) return addresses.stream().limit(MAX_STREETS).toList();
        return streets.stream()
                .map(
                        street ->
                                new PlaceSuggestion(
                                        Kind.STREET,
                                        street.name(),
                                        street.commune(),
                                        null,
                                        street.position()))
                .toList();
    }

    private static PlaceSuggestion stop(TransitStop stop) {
        return new PlaceSuggestion(
                Kind.STOP,
                stop.name(),
                stop.lines().stream()
                        .limit(MAX_LINES_SHOWN)
                        .map(TransitLine::name)
                        .collect(Collectors.joining(", ")),
                stop.mode(),
                stop.position());
    }
}
