package fr.emploigo.establishment.domain;

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/** A NAF sub-class, such as {@code 56.10A} in NAF rév. 2 or {@code 56.11G} in NAF 2025. */
public record ActivityCode(Nomenclature nomenclature, String code) {
    private static final Pattern SUB_CLASS = Pattern.compile("[0-9]{2}\\.[0-9]{2}[A-Z]");

    public ActivityCode {
        Objects.requireNonNull(nomenclature);
        if (code == null || !SUB_CLASS.matcher(code).matches())
            throw new IllegalArgumentException("Not a NAF sub-class: " + code);
    }

    /** The sub-class of a code, empty for an absent or malformed one. */
    public static Optional<ActivityCode> of(Nomenclature nomenclature, String code) {
        return code != null && SUB_CLASS.matcher(code).matches()
                ? Optional.of(new ActivityCode(nomenclature, code))
                : Optional.empty();
    }
}
