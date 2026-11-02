package fr.emploigo.establishment.application.search;

import fr.emploigo.establishment.domain.TransitStop;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** The places given, found whatever the words; a number is found by its value and label. */
public final class FakePlaceRepository implements PlaceRepository {
    private final List<CommuneMatch> communes = new ArrayList<>();
    private final List<StreetMatch> streets = new ArrayList<>();
    private final List<NumberMatch> numbers = new ArrayList<>();
    private final List<TransitStop> stops = new ArrayList<>();

    public FakePlaceRepository add(CommuneMatch commune) {
        communes.add(commune);
        return this;
    }

    public FakePlaceRepository add(StreetMatch street) {
        streets.add(street);
        return this;
    }

    public FakePlaceRepository add(NumberMatch number) {
        numbers.add(number);
        return this;
    }

    public FakePlaceRepository add(TransitStop stop) {
        stops.add(stop);
        return this;
    }

    @Override
    public List<CommuneMatch> findCommunes(String words, int limit) {
        return communes.stream().limit(limit).toList();
    }

    @Override
    public List<StreetMatch> findStreets(String words, int limit) {
        return streets.stream().limit(limit).toList();
    }

    @Override
    public List<NumberMatch> findNumbers(
            Collection<Integer> streetIds, int number, Optional<String> suffix) {
        String label = number + suffix.map(index -> " " + index).orElse("");
        return numbers.stream()
                .filter(match -> streetIds.contains(match.streetId()))
                .filter(
                        match ->
                                suffix.isPresent()
                                        ? match.label().equals(label)
                                        : match.label().split(" ")[0].equals(label))
                .toList();
    }

    @Override
    public List<TransitStop> findStops(String words, int limit) {
        return stops.stream().limit(limit).toList();
    }
}
