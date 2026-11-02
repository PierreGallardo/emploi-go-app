package fr.emploigo.establishment.domain;

import java.util.Optional;
import java.util.Set;

/**
 * The INSEE range of salaried staff of an establishment, by its code ({@code 11}: 10 to 19
 * employees). {@code NN} (no employee in the reference year, or unknown) is no range.
 */
public record HeadcountRange(String code) {
    private static final Set<String> CODES =
            Set.of(
                    "00", "01", "02", "03", "11", "12", "21", "22", "31", "32", "41", "42", "51",
                    "52", "53");

    public HeadcountRange {
        if (!isKnown(code)) throw new IllegalArgumentException("Unknown headcount range: " + code);
    }

    /** The range of a code of the directory, empty for {@code NN}, an absent or unknown code. */
    public static Optional<HeadcountRange> of(String code) {
        return isKnown(code) ? Optional.of(new HeadcountRange(code)) : Optional.empty();
    }

    private static boolean isKnown(String code) {
        return code != null && CODES.contains(code);
    }
}
