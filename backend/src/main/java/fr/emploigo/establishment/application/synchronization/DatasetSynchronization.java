package fr.emploigo.establishment.application.synchronization;

/** What one import of a data set did, and how many items it wrote when it replaced them. */
public record DatasetSynchronization(Outcome outcome, int written) {
    public enum Outcome {
        UPDATED,
        UNCHANGED,
        /** The worker is stopping: the next run waits for the daily hour. */
        INTERRUPTED
    }

    static DatasetSynchronization updated(int written) {
        return new DatasetSynchronization(Outcome.UPDATED, written);
    }

    static DatasetSynchronization unchanged() {
        return new DatasetSynchronization(Outcome.UNCHANGED, 0);
    }
}
