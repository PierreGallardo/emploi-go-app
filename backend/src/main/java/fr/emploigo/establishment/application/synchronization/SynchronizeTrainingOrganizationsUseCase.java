package fr.emploigo.establishment.application.synchronization;

import fr.emploigo.config.WorkerOnly;
import fr.emploigo.establishment.application.synchronization.TrainingOrganizationFileClient.Result;

import io.micrometer.core.instrument.MeterRegistry;

import org.springframework.stereotype.Service;

import java.time.Clock;

/** Replaces the training organisations by those of the public list, once it is published again. */
@Service
@WorkerOnly
public class SynchronizeTrainingOrganizationsUseCase {
    private static final String DATASET = "training-organizations";

    private final TrainingOrganizationFileClient file;
    private final TrainingOrganizationRepository trainers;
    private final Clock clock;
    private final ImportRuns<DatasetSynchronization> runs;

    public SynchronizeTrainingOrganizationsUseCase(
            TrainingOrganizationFileClient file,
            TrainingOrganizationRepository trainers,
            Clock clock,
            MeterRegistry meters) {
        this.file = file;
        this.trainers = trainers;
        this.clock = clock;
        this.runs = ImportRuns.ofDataset(DATASET, "organismes de formation", meters);
    }

    /** Throws when the file cannot be read or lists none, keeping the list in place. */
    public DatasetSynchronization synchronize() {
        return runs.record(
                () ->
                        switch (file.fetch(trainers.findPublishedAt())) {
                            case Result.Unchanged unchanged -> DatasetSynchronization.unchanged();
                            case Result.Unavailable unavailable ->
                                    runs.unavailable(unavailable.cause());
                            case Result.Published published -> replace(published);
                        });
    }

    private DatasetSynchronization replace(Result.Published published) {
        if (published.sirets().isEmpty())
            throw new DatasetUnavailableException(DATASET, "aucun organisme de formation");
        trainers.replace(published.sirets(), published.publishedAt(), clock.instant());
        return DatasetSynchronization.updated(published.sirets().size());
    }
}
