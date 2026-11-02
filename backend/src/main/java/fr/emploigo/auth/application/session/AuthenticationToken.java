package fr.emploigo.auth.application.session;

import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The only place that reads the token format: a text that does not match is refused the same way by
 * every route, before any database access.
 */
public record AuthenticationToken(UUID id, String secret) {
    private static final int LENGTH = 80;

    /**
     * Not {@code UUID.fromString}, which accepts other spellings: a token has one form, in lower
     * case.
     */
    private static final Pattern FORMAT =
            Pattern.compile(
                    "([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\\.([A-Za-z0-9_-]{43})");

    @Override
    public String toString() {
        return "AuthenticationToken[redacted]";
    }

    public static Optional<AuthenticationToken> parse(String text) {
        if (text == null || text.length() != LENGTH) return Optional.empty();
        var matcher = FORMAT.matcher(text);
        if (!matcher.matches()) return Optional.empty();
        return Optional.of(
                new AuthenticationToken(UUID.fromString(matcher.group(1)), matcher.group(2)));
    }
}
