package fr.emploigo.auth.domain;

/**
 * The password rule of a registration and of a reset, counting lengths in UTF-16 units as
 * {@code @Size} does. A special character is any code point that is no letter, digit or combining
 * mark, so an accent typed as a separate mark is not special. The frontend applies the same rule
 * ({@code password-rule.ts}); both read {@code contracts/password-rule-cases.json}.
 */
public final class RegistrationPassword {
    private static final int MIN_LENGTH = 8;
    private static final int MAX_LENGTH = 128;

    private RegistrationPassword() {}

    /** Whether the length and the composition are those of the rule. */
    public static boolean meetsRule(String password) {
        if (password == null || password.length() < MIN_LENGTH || password.length() > MAX_LENGTH)
            return false;
        boolean uppercase = false, lowercase = false, digit = false, special = false;
        for (int i = 0; i < password.length(); ) {
            int point = password.codePointAt(i);
            i += Character.charCount(point);
            switch (Character.getType(point)) {
                case Character.UPPERCASE_LETTER -> uppercase = true;
                case Character.LOWERCASE_LETTER -> lowercase = true;
                case Character.DECIMAL_DIGIT_NUMBER -> digit = true;
                case Character.TITLECASE_LETTER,
                        Character.MODIFIER_LETTER,
                        Character.OTHER_LETTER,
                        Character.NON_SPACING_MARK,
                        Character.COMBINING_SPACING_MARK,
                        Character.ENCLOSING_MARK -> {}
                default -> special = true;
            }
        }
        return uppercase && lowercase && digit && special;
    }
}
