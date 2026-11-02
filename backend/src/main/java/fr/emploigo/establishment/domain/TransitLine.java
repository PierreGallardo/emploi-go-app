package fr.emploigo.establishment.domain;

import java.math.BigInteger;
import java.util.Comparator;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** A line of the Divia network, by its short name ({@code T1}, {@code L5}, {@code 10}). */
public record TransitLine(String name, TransitMode mode) {
    public static final int MAX_NAME_LENGTH = 20;
    private static final Pattern NUMBER_OR_TEXT = Pattern.compile("[0-9]+|[^0-9]+");

    /**
     * The order of the network: {@code 10} before {@code 12} before {@code L3} before {@code T1}.
     */
    public static final Comparator<TransitLine> ORDER = (a, b) -> compareNames(a.name, b.name);

    public TransitLine {
        Objects.requireNonNull(mode);
        if (name == null || name.isBlank() || name.length() > MAX_NAME_LENGTH)
            throw new IllegalArgumentException("Not a line name: " + name);
    }

    /** Compares the numbers in two names by their value, and the rest ignoring case. */
    private static int compareNames(String a, String b) {
        var left = NUMBER_OR_TEXT.matcher(a);
        var right = NUMBER_OR_TEXT.matcher(b);
        while (left.find() && right.find()) {
            String x = left.group();
            String y = right.group();
            boolean xNumber = Character.isDigit(x.charAt(0));
            boolean yNumber = Character.isDigit(y.charAt(0));
            int order;
            if (xNumber && yNumber) {
                order = new BigInteger(x).compareTo(new BigInteger(y));
            } else if (xNumber || yNumber) {
                order = xNumber ? -1 : 1;
            } else {
                order = x.toLowerCase(Locale.ROOT).compareTo(y.toLowerCase(Locale.ROOT));
            }
            if (order != 0) return order;
        }
        return Integer.compare(a.length(), b.length());
    }
}
