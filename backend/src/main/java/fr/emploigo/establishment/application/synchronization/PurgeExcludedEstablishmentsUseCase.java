package fr.emploigo.establishment.application.synchronization;

import fr.emploigo.config.WorkerOnly;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Deletes the establishments excluded for longer than the retention: closed, absent from the
 * directory or no longer meeting a rule of the import. A withheld one is deleted by the import.
 */
@Service
@WorkerOnly
public class PurgeExcludedEstablishmentsUseCase {
    private static final Logger LOG =
            LoggerFactory.getLogger(PurgeExcludedEstablishmentsUseCase.class);

    /** How long an excluded establishment stays, hidden, before it is deleted. */
    public static final Duration RETENTION = Duration.ofDays(90);

    static final int BATCH_SIZE = 500;
    static final int MAX_BATCHES = 100;

    private final EstablishmentRepository establishments;
    private final Clock clock;

    public PurgeExcludedEstablishmentsUseCase(EstablishmentRepository establishments, Clock clock) {
        this.establishments = establishments;
        this.clock = clock;
    }

    /** Deletes by batches, each its own transaction; returns how many were deleted. */
    public int purge() {
        Instant excludedBefore = clock.instant().minus(RETENTION);
        int deleted = 0;
        for (int batch = 0; batch < MAX_BATCHES; batch++) {
            int count = establishments.deleteExcluded(excludedBefore, BATCH_SIZE);
            deleted += count;
            if (count < BATCH_SIZE) break;
        }
        LOG.info("Purge des établissements exclus : {}", deleted);
        return deleted;
    }
}
