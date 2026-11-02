package fr.emploigo.establishment.application.synchronization;

import fr.emploigo.establishment.domain.TransitPlatform;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** The GTFS feed of DiviaMobilités, published on data.gouv.fr. */
public interface TransitFeedClient {
    /**
     * The platforms served by a line, unless the feed was not published after {@code
     * knownPublication}.
     */
    Result fetch(Optional<Instant> knownPublication);

    sealed interface Result {
        record Published(Instant publishedAt, List<TransitPlatform> platforms) implements Result {
            public Published {
                platforms = List.copyOf(platforms);
            }
        }

        record Unchanged() implements Result {}

        /** No answer, or a feed the client cannot read whole. */
        record Unavailable(String cause) implements Result {}
    }
}
