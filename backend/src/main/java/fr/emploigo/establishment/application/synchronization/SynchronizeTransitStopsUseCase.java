package fr.emploigo.establishment.application.synchronization;

import fr.emploigo.config.WorkerOnly;
import fr.emploigo.establishment.application.synchronization.TransitFeedClient.Result;
import fr.emploigo.establishment.domain.TransitStop;

import io.micrometer.core.instrument.MeterRegistry;

import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;

/** Replaces the stops of the Divia network by those of its feed, once it is published again. */
@Service
@WorkerOnly
public class SynchronizeTransitStopsUseCase {
    private static final String DATASET = "transit-stops";

    private final TransitFeedClient feed;
    private final TransitStopRepository stops;
    private final Clock clock;
    private final ImportRuns<DatasetSynchronization> runs;

    public SynchronizeTransitStopsUseCase(
            TransitFeedClient feed,
            TransitStopRepository stops,
            Clock clock,
            MeterRegistry meters) {
        this.feed = feed;
        this.stops = stops;
        this.clock = clock;
        this.runs = ImportRuns.ofDataset(DATASET, "arrêts Divia", meters);
    }

    /** Throws when the feed cannot be read or serves no stop, keeping the stops in place. */
    public DatasetSynchronization synchronize() {
        return runs.record(
                () ->
                        switch (feed.fetch(stops.findPublishedAt())) {
                            case Result.Unchanged unchanged -> DatasetSynchronization.unchanged();
                            case Result.Unavailable unavailable ->
                                    runs.unavailable(unavailable.cause());
                            case Result.Published published -> replace(published);
                        });
    }

    private DatasetSynchronization replace(Result.Published published) {
        List<TransitStop> served = TransitStop.group(published.platforms());
        if (served.isEmpty())
            throw new DatasetUnavailableException(DATASET, "aucun arrêt desservi");
        stops.replace(served, published.publishedAt(), clock.instant());
        return DatasetSynchronization.updated(served.size());
    }
}
