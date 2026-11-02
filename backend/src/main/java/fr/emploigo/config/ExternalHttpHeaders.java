package fr.emploigo.config;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;

/** What the requests to an external service say of us, and how their dates are written. */
public final class ExternalHttpHeaders {
    public static final String USER_AGENT = "Emploi Go (Mission Locale de Dijon)";

    // RFC 9110 dates: two-digit day, English names, always in GMT.
    private static final DateTimeFormatter HTTP_DATE =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.ENGLISH)
                    .withZone(ZoneOffset.UTC);

    private ExternalHttpHeaders() {}

    /** An instant as a date header writes it. */
    public static String date(Instant instant) {
        return HTTP_DATE.format(instant);
    }

    /** The instant of a date header, empty when it is not one. */
    public static Optional<Instant> instant(String date) {
        try {
            return Optional.of(
                    ZonedDateTime.parse(date, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant());
        } catch (DateTimeException unreadable) {
            return Optional.empty();
        }
    }
}
