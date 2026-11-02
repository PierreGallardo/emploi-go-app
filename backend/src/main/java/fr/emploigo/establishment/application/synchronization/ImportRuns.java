package fr.emploigo.establishment.application.synchronization;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

/** Counts and logs the runs of one import, by outcome. */
final class ImportRuns<R> {
    private static final Logger LOG = LoggerFactory.getLogger(ImportRuns.class);
    private static final String FAILED = "failed";

    private final String dataset;
    private final String items;
    private final Function<R, Enum<?>> outcome;
    private final Function<R, Optional<Object>> detail;
    private final MeterRegistry meters;

    /**
     * @param dataset the tag of the metrics ({@code transit-stops})
     * @param items what the import writes, in French ({@code arrêts Divia})
     * @param outcomes every outcome a run may have
     * @param detail what the log of a run adds to its outcome, if anything
     */
    ImportRuns(
            String dataset,
            String items,
            Enum<?>[] outcomes,
            Function<R, Enum<?>> outcome,
            Function<R, Optional<Object>> detail,
            MeterRegistry meters) {
        this.dataset = dataset;
        this.items = items;
        this.outcome = outcome;
        this.detail = detail;
        this.meters = meters;
        // Every series exists from the start, so that an increase has a point to start from.
        for (Enum<?> each : outcomes) runs(each.name());
        runs(FAILED);
    }

    /** The import of a data set file, which details the number of items it wrote. */
    static ImportRuns<DatasetSynchronization> ofDataset(
            String dataset, String items, MeterRegistry meters) {
        return new ImportRuns<>(
                dataset,
                items,
                DatasetSynchronization.Outcome.values(),
                DatasetSynchronization::outcome,
                result ->
                        result.outcome() == DatasetSynchronization.Outcome.UPDATED
                                ? Optional.of(result.written())
                                : Optional.empty(),
                meters);
    }

    R record(Supplier<R> run) {
        R result;
        try {
            result = run.get();
        } catch (RuntimeException error) {
            runs(FAILED).increment();
            throw error;
        }
        String name = outcome.apply(result).name();
        runs(name).increment();
        String shown = name.toLowerCase(Locale.ROOT);
        detail.apply(result)
                .ifPresentOrElse(
                        more -> LOG.info("Import des {} : {} ({})", items, shown, more),
                        () -> LOG.info("Import des {} : {}", items, shown));
        return result;
    }

    /** A file left unread fails the run, unless the worker is stopping. */
    DatasetSynchronization unavailable(String cause) {
        if (Thread.currentThread().isInterrupted())
            return new DatasetSynchronization(DatasetSynchronization.Outcome.INTERRUPTED, 0);
        throw new DatasetUnavailableException(dataset, cause);
    }

    private Counter runs(String outcome) {
        return Counter.builder("dataset.synchronization.runs")
                .tag("dataset", dataset)
                .tag("outcome", outcome.toLowerCase(Locale.ROOT))
                .description("Exécutions des imports, par jeu de données")
                .register(meters);
    }
}
