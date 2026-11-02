package fr.emploigo.establishment.application.synchronization;

import fr.emploigo.config.WorkerOnly;
import fr.emploigo.establishment.application.synchronization.EstablishmentDirectoryClient.Result;
import fr.emploigo.establishment.domain.ActivityCode;
import fr.emploigo.establishment.domain.Assessment;
import fr.emploigo.establishment.domain.ExclusionReason;
import fr.emploigo.establishment.domain.Siret;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Brings the establishments of Dijon Métropole up to date with the Sirene directory, by SIRET.
 * Only a complete run marks as not found the establishments it did not see: a run that misses
 * results would otherwise hide establishments that still exist.
 */
@Service
@WorkerOnly
public class SynchronizeEstablishmentsUseCase {
    private static final Logger LOG =
            LoggerFactory.getLogger(SynchronizeEstablishmentsUseCase.class);
    private static final String RETAINED = "retained";
    private static final String WITHHELD = "withheld";
    private static final String NOT_FOUND = label(ExclusionReason.NOT_FOUND);

    public enum Outcome {
        /** Every page read in full: the establishments not seen are marked not found. */
        COMPLETE,
        /** Some results could not be read: nothing is marked not found. */
        INCOMPLETE,
        /** The worker is stopping: the next run starts over. */
        INTERRUPTED
    }

    /** What a run did, by result of the establishments ({@code retained}, a reason…). */
    public record Synchronization(Outcome outcome, Map<String, Integer> establishments) {}

    private final EstablishmentDirectoryClient directory;
    private final EstablishmentRepository establishments;
    private final ActivityRepository activities;
    private final CommuneRepository communes;
    private final TrainingOrganizationRepository trainers;
    private final Clock clock;
    private final ImportRuns<Synchronization> runs;
    private final Map<String, Counter> establishmentCounters = new LinkedHashMap<>();

    public SynchronizeEstablishmentsUseCase(
            EstablishmentDirectoryClient directory,
            EstablishmentRepository establishments,
            ActivityRepository activities,
            CommuneRepository communes,
            TrainingOrganizationRepository trainers,
            Clock clock,
            MeterRegistry meters) {
        this.directory = directory;
        this.establishments = establishments;
        this.activities = activities;
        this.communes = communes;
        this.trainers = trainers;
        this.clock = clock;
        // Every series exists from the start, so that an increase has a point to start from.
        var results = new ArrayList<>(List.of(RETAINED, WITHHELD));
        for (ExclusionReason reason : ExclusionReason.values()) results.add(label(reason));
        for (String result : results)
            establishmentCounters.put(
                    result,
                    Counter.builder("establishment.synchronization.establishments")
                            .tag("result", result)
                            .description("Établissements de l'annuaire traités par l'import")
                            .register(meters));
        runs =
                new ImportRuns<>(
                        "establishments",
                        "établissements",
                        Outcome.values(),
                        Synchronization::outcome,
                        result -> Optional.of(result.establishments()),
                        meters);
    }

    /**
     * Runs the whole import; throws when the directory or the database fails, keeping what it
     * wrote.
     */
    public Synchronization synchronize() {
        return runs.record(this::run);
    }

    private Synchronization run() {
        // PostgreSQL keeps microseconds: a finer instant would be later than its own stored copy.
        Instant startedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        var run = new Run(activities.findClassified(), trainers.findSirets(), startedAt);
        List<String> communeCodes = communes.findCodes();
        // Without commune, or without any establishment, the answer would miss its results.
        if (communeCodes.isEmpty()) return run.end(Outcome.INCOMPLETE);
        int read = 0;
        int announced = 0;
        String cursor = null;
        for (int number = 1; ; number++) {
            if (Thread.currentThread().isInterrupted()) return run.end(Outcome.INTERRUPTED);
            Result.Page page = page(communeCodes, cursor, number);
            if (page == null) return run.end(Outcome.INTERRUPTED);
            save(page, run);
            read += page.establishments().size();
            announced = page.total();
            cursor = page.nextCursor();
            if (cursor == null) break;
        }
        // The client leaves out an establishment it cannot read, and the directory may move during
        // the run: either way, the total announced is not reached.
        if (read != announced) {
            LOG.warn(
                    "Import des établissements : {} établissements lus sur {} annoncés",
                    read,
                    announced);
            return run.end(Outcome.INCOMPLETE);
        }
        if (read == 0) return run.end(Outcome.INCOMPLETE);
        int notFound = establishments.markNotFound(startedAt);
        run.count(NOT_FOUND, notFound);
        return run.end(Outcome.COMPLETE);
    }

    /** A page of the directory; null when the worker is stopping. */
    private Result.Page page(List<String> communeCodes, String cursor, int number) {
        return switch (directory.read(communeCodes, cursor)) {
            case Result.Page page -> page;
            case Result.Unavailable unavailable -> {
                if (Thread.currentThread().isInterrupted()) yield null;
                throw new DirectoryUnavailableException(number, unavailable.cause());
            }
        };
    }

    private void save(Result.Page page, Run run) {
        List<Assessment> assessments =
                page.establishments().stream()
                        .map(e -> e.assess(run.classified, run.trainers))
                        .toList();
        establishments.save(assessments, run.startedAt);
        for (Assessment assessment : assessments)
            run.count(
                    switch (assessment) {
                        case Assessment.Retained retained -> RETAINED;
                        case Assessment.Excluded excluded -> label(excluded.reason());
                        case Assessment.Withheld withheld -> WITHHELD;
                    },
                    1);
    }

    private static String label(ExclusionReason reason) {
        return reason.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /** The state of one run. */
    private final class Run {
        final Set<ActivityCode> classified;
        final Set<Siret> trainers;
        final Instant startedAt;
        final Map<String, Integer> counts = new LinkedHashMap<>();

        Run(Set<ActivityCode> classified, Set<Siret> trainers, Instant startedAt) {
            this.classified = classified;
            this.trainers = trainers;
            this.startedAt = startedAt;
        }

        void count(String result, int amount) {
            counts.merge(result, amount, Integer::sum);
            establishmentCounters.get(result).increment(amount);
        }

        Synchronization end(Outcome outcome) {
            return new Synchronization(outcome, Map.copyOf(counts));
        }
    }
}
