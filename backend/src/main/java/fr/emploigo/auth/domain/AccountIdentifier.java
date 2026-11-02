package fr.emploigo.auth.domain;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

public final class AccountIdentifier {
    // Checked before lowering the case: the Kelvin sign, for one, would become an ASCII k.
    private static final Pattern USERNAME = Pattern.compile("(?!.*\\.\\.)[A-Za-z0-9_.]{2,32}");

    private AccountIdentifier() {}

    /**
     * The username as stored, and its own key: 2 to 32 unaccented letters, digits, {@code _} or
     * {@code .}, never two dots in a row, in lower case.
     *
     * @throws IllegalArgumentException if {@code value} breaks the rule
     */
    public static String username(String value) {
        String result = value.strip();
        if (!USERNAME.matcher(result).matches()) {
            throw new IllegalArgumentException("Pseudo invalide");
        }
        return result.toLowerCase(Locale.ROOT);
    }

    public static String emailKey(String value) {
        String result = value.strip().toLowerCase(Locale.ROOT);
        if (result.length() > 320 || !result.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) {
            throw new IllegalArgumentException("Adresse invalide");
        }
        return result;
    }

    public static String loginKey(String value) {
        return value.contains("@") ? emailKey(value) : username(value);
    }

    /** The login key of a typed identifier, or empty when no account can have it. */
    public static Optional<String> readableLoginKey(String value) {
        try {
            return Optional.of(loginKey(value));
        } catch (IllegalArgumentException error) {
            return Optional.empty();
        }
    }
}
