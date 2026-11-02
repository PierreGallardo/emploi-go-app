package fr.emploigo.auth.domain;

import java.math.BigInteger;
import java.time.Duration;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The code of six digits that confirms a registration, and the limits that keep it from being
 * guessed: attempts per code, failures over all the codes of a registration, and the hour without
 * any new code that follows every {@link #FAILURES_PER_BLOCK} failures.
 */
public final class RegistrationCode {
    public static final Duration VALIDITY = Duration.ofMinutes(15);
    public static final int MAX_ATTEMPTS = 5;
    public static final int FAILURES_PER_BLOCK = 15;
    public static final Duration BLOCK = Duration.ofHours(1);

    private static final int DIGITS = 6;
    private static final BigInteger SPACE = BigInteger.TEN.pow(DIGITS);
    private static final Pattern TYPED = Pattern.compile("[0-9]{" + DIGITS + "}");
    private static final Pattern SEPARATORS = Pattern.compile("[\\s\\-]");

    private RegistrationCode() {}

    /**
     * The code as typed, without the spaces and hyphens a copy may bring, or empty if it is not six
     * digits.
     */
    public static Optional<String> normalize(String typed) {
        if (typed == null) return Optional.empty();
        String digits = SEPARATORS.matcher(typed).replaceAll("");
        return TYPED.matcher(digits).matches() ? Optional.of(digits) : Optional.empty();
    }

    /** Six digits from the first 8 bytes of a digest: the bias of the modulo is below 10^-12. */
    public static String fromDigest(byte[] digest) {
        var value = new BigInteger(1, java.util.Arrays.copyOf(digest, 8)).mod(SPACE);
        return String.format("%0" + DIGITS + "d", value);
    }
}
