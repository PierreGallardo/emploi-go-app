package fr.emploigo.establishment.domain;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A street of a commune and its numbers, each label once, in increasing order. The street lies at
 * its number closest to the middle of them all, which is on the street even when it bends.
 */
public record Street(
        String communeCode, String name, Position position, List<HouseNumber> numbers) {
    public static final int MAX_NAME_LENGTH = 200;
    private static final Pattern COMMUNE_CODE = Pattern.compile("[0-9]{5}");

    public Street {
        Objects.requireNonNull(position);
        if (communeCode == null || !COMMUNE_CODE.matcher(communeCode).matches())
            throw new IllegalArgumentException("Not a commune code: " + communeCode);
        if (name == null || name.isBlank() || name.length() > MAX_NAME_LENGTH)
            throw new IllegalArgumentException("Not a street name: " + name);
        if (numbers.isEmpty()) throw new IllegalArgumentException("A street has a number.");
        numbers = List.copyOf(numbers);
    }

    /** The streets of the addresses, by commune and name; a label given twice keeps the first. */
    public static List<Street> group(Collection<Address> addresses) {
        Map<List<String>, Map<String, HouseNumber>> streets = new LinkedHashMap<>();
        for (Address address : addresses)
            streets.computeIfAbsent(
                            List.of(address.communeCode(), address.street()),
                            key -> new LinkedHashMap<>())
                    .putIfAbsent(address.number().label(), address.number());
        return streets.entrySet().stream()
                .map(
                        street ->
                                of(
                                        street.getKey().get(0),
                                        street.getKey().get(1),
                                        street.getValue().values()))
                .toList();
    }

    private static Street of(String communeCode, String name, Collection<HouseNumber> numbers) {
        Position middle =
                new Position(
                        numbers.stream()
                                .mapToDouble(number -> number.position().longitude())
                                .average()
                                .orElseThrow(),
                        numbers.stream()
                                .mapToDouble(number -> number.position().latitude())
                                .average()
                                .orElseThrow());
        HouseNumber closest =
                numbers.stream()
                        .min(Comparator.comparingDouble(n -> n.position().metersTo(middle)))
                        .orElseThrow();
        return new Street(
                communeCode,
                name,
                closest.position(),
                numbers.stream()
                        .sorted(
                                Comparator.comparingInt(HouseNumber::number)
                                        .thenComparing(HouseNumber::label))
                        .toList());
    }
}
