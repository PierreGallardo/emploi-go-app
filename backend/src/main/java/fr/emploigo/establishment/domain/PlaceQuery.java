package fr.emploigo.establishment.domain;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a youth typed in « Où ? »: the words of a street, a commune or a stop, after a house number
 * when the text starts with one (« 6 bis avenue Foch »).
 */
public record PlaceQuery(Optional<Integer> number, Optional<String> suffix, String words) {
    public static final int MAX_LENGTH = 100;

    private static final Pattern NUMBERED =
            Pattern.compile("(\\d{1,5})(?:\\s*(bis|ter|quater|[a-z])(?=\\s))?\\s+(.+)");

    public PlaceQuery {
        Objects.requireNonNull(number);
        Objects.requireNonNull(suffix);
        Objects.requireNonNull(words);
    }

    /** The query of a text, empty when it has too few letters to search. */
    public static Optional<PlaceQuery> of(String text) {
        String typed = text.strip().toLowerCase(Locale.ROOT);
        Matcher numbered = NUMBERED.matcher(typed);
        PlaceQuery query =
                numbered.matches()
                        ? new PlaceQuery(
                                Optional.of(Integer.parseInt(numbered.group(1))),
                                Optional.ofNullable(numbered.group(2)),
                                numbered.group(3))
                        : new PlaceQuery(Optional.empty(), Optional.empty(), typed);
        return SuggestedText.isLongEnough(query.words()) ? Optional.of(query) : Optional.empty();
    }
}
