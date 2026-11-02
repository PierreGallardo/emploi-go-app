package fr.emploigo.establishment.domain;

import java.util.Optional;
import java.util.regex.Pattern;

/** The identifier of an establishment: the SIREN of its company, then five digits. */
public record Siret(String value) {
    private static final Pattern DIGITS = Pattern.compile("[0-9]{14}");

    public Siret {
        if (value == null || !DIGITS.matcher(value).matches())
            throw new IllegalArgumentException("A SIRET has 14 digits.");
    }

    /** The SIRET of a value, empty for an absent or malformed one. */
    public static Optional<Siret> of(String value) {
        return value != null && DIGITS.matcher(value).matches()
                ? Optional.of(new Siret(value))
                : Optional.empty();
    }
}
