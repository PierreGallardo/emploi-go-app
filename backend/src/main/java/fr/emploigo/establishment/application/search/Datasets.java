package fr.emploigo.establishment.application.search;

import java.time.Instant;
import java.util.List;

/**
 * The open data the map shows beside the establishments: the Divia stops, and when the Divia feed
 * and the Base Adresse Nationale were published, for their mention. A data set not imported yet has
 * no date.
 */
public record Datasets(
        List<StopLocation> stops, Instant transitPublishedAt, Instant addressesPublishedAt) {
    public Datasets {
        stops = List.copyOf(stops);
    }
}
