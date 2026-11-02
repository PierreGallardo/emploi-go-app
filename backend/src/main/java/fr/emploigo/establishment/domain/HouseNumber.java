package fr.emploigo.establishment.domain;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A number of a street, with its repetition index ({@code bis}, {@code ter}, {@code a}) when it has
 * one.
 */
public record HouseNumber(int number, String suffix, Position position) {
    public static final int MAX_NUMBER = 99_998;
    private static final Pattern NUMBER = Pattern.compile("[0-9]{1,5}");
    private static final Pattern SUFFIX = Pattern.compile("[a-z]{1,10}");

    public HouseNumber {
        Objects.requireNonNull(position);
        if (number < 1 || number > MAX_NUMBER)
            throw new IllegalArgumentException("Not a house number: " + number);
        if (suffix != null && !SUFFIX.matcher(suffix).matches())
            throw new IllegalArgumentException("Not a repetition index: " + suffix);
    }

    /**
     * The house number of a number and a repetition index as written, the index in any case and
     * empty when there is none; empty when they are not one.
     */
    public static Optional<HouseNumber> parse(String number, String suffix, Position position) {
        String index = suffix.toLowerCase(Locale.ROOT);
        if (!NUMBER.matcher(number).matches()
                || !index.isEmpty() && !SUFFIX.matcher(index).matches()) return Optional.empty();
        int value = Integer.parseInt(number);
        if (value < 1 || value > MAX_NUMBER) return Optional.empty();
        return Optional.of(new HouseNumber(value, index.isEmpty() ? null : index, position));
    }

    /** The number as shown: {@code 6 bis}. */
    public String label() {
        return suffix == null ? Integer.toString(number) : number + " " + suffix;
    }
}
