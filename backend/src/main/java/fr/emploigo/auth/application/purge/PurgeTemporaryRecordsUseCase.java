package fr.emploigo.auth.application.purge;

import fr.emploigo.auth.domain.TemporaryRetention;
import fr.emploigo.config.WorkerOnly;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.IntSupplier;

/**
 * Purges the expired temporary data of the authentication at each worker run, at the delays of
 * docs/backend/donnees.md; the expiry of each item is checked at every use, so a late purge never
 * lets anything be used, and a kind that fails does not stop the others unless the database cannot
 * be reached.
 */
@Service
@WorkerOnly
public class PurgeTemporaryRecordsUseCase {
    /**
     * The batches of one kind in one run: {@code MAX_BATCHES *
     * AuthenticationPurgeRepository.BATCH_SIZE} rows at most.
     */
    public static final int MAX_BATCHES = 100;

    private static final Logger LOG = LoggerFactory.getLogger(PurgeTemporaryRecordsUseCase.class);
    private static final String ROWS = "auth.purge.rows";

    /**
     * The kinds of data, in the order of the purge: what holds the data of a young person first.
     */
    enum Category {
        REGISTRATIONS_EXPIRED,
        REGISTRATIONS,
        PASSWORD_RESETS,
        GOOGLE_FLOWS,
        GOOGLE_PENDING,
        GOOGLE_VERIFICATIONS,
        RECONNECTIONS;

        String label() {
            return name().toLowerCase(Locale.ROOT).replace('_', '-');
        }
    }

    /**
     * What a run handled, by kind of data; {@code lagging} lists the kinds whose run stopped at the
     * bound.
     */
    public record Purged(Map<String, Integer> rows, List<String> lagging) {
        public int total() {
            return rows.values().stream().mapToInt(Integer::intValue).sum();
        }
    }

    private record Step(Category data, IntSupplier batch) {}

    private final AuthenticationPurgeRepository purge;
    private final Clock clock;
    private final Map<Category, Counter> counters = new EnumMap<>(Category.class);

    public PurgeTemporaryRecordsUseCase(
            AuthenticationPurgeRepository purge, Clock clock, MeterRegistry meters) {
        this.purge = purge;
        this.clock = clock;
        // Every kind exists from the start, so that an increase has a point to start from.
        for (Category data : Category.values()) {
            counters.put(
                    data,
                    Counter.builder(ROWS)
                            .tag("data", data.label())
                            .description(
                                    "Lignes traitées par la purge des données temporaires d'authentification")
                            .register(meters));
        }
    }

    /**
     * Purges what is due at the instant of the call. Throws the first failure after having tried
     * every kind.
     */
    public Purged purge() {
        Instant now = clock.instant();
        Map<String, Integer> rows = new LinkedHashMap<>();
        List<String> lagging = new ArrayList<>();
        RuntimeException failure = null;
        for (Category data : Category.values()) rows.put(data.label(), 0);
        boolean interrupted = false;
        for (Step step : steps(now)) {
            interrupted = Thread.currentThread().isInterrupted();
            if (interrupted) break;
            try {
                drain(step, rows, lagging);
            } catch (RuntimeException error) {
                // The worker is stopping: an interrupted run is not a failure, and the next one
                // continues.
                interrupted = Thread.currentThread().isInterrupted();
                if (interrupted) break;
                if (failure == null) failure = error;
                else failure.addSuppressed(error);
                // Without a connection, the other kinds would each wait for one in turn for
                // nothing; a transaction that cannot start reports it as a TransactionException.
                if (error instanceof DataAccessResourceFailureException
                        || error instanceof TransactionException) break;
            }
        }
        Purged result = new Purged(rows, List.copyOf(lagging));
        if (result.total() > 0)
            LOG.info(
                    "Purge des données temporaires d'authentification : {} lignes traitées",
                    result.total());
        if (interrupted)
            LOG.info(
                    "Purge des données temporaires d'authentification interrompue : la suite à la prochaine exécution");
        if (failure != null) throw failure;
        return result;
    }

    /**
     * Handles the batches of one kind, counting each one as soon as it is done: a failure keeps
     * what the earlier batches did.
     */
    private void drain(Step step, Map<String, Integer> rows, List<String> lagging) {
        String label = step.data().label();
        for (int batches = 0; batches < MAX_BATCHES; batches++) {
            int batch = step.batch().getAsInt();
            rows.merge(label, batch, Integer::sum);
            counters.get(step.data()).increment(batch);
            if (batch < AuthenticationPurgeRepository.BATCH_SIZE
                    || Thread.currentThread().isInterrupted()) return;
        }
        // Without an address or an identifier: only the kind of data that lags, once for the run.
        if (!lagging.contains(label)) {
            lagging.add(label);
            LOG.warn(
                    "Purge des données temporaires d'authentification en retard : {} (la suite à la prochaine exécution)",
                    label);
        }
    }

    private List<Step> steps(Instant now) {
        var steps = new ArrayList<Step>();
        steps.add(new Step(Category.REGISTRATIONS_EXPIRED, () -> purge.expireRegistrations(now)));
        steps.add(
                new Step(
                        Category.REGISTRATIONS,
                        () ->
                                purge.deleteRegistrations(
                                        now.minus(TemporaryRetention.REGISTRATION))));
        steps.add(
                new Step(
                        Category.PASSWORD_RESETS,
                        () ->
                                purge.deletePasswordResets(
                                        now.minus(TemporaryRetention.PASSWORD_RESET))));
        steps.add(
                new Step(
                        Category.GOOGLE_FLOWS,
                        () ->
                                purge.deleteGoogleFlows(
                                        now.minus(TemporaryRetention.GOOGLE_FLOW))));
        steps.add(
                new Step(
                        Category.GOOGLE_PENDING,
                        () ->
                                purge.deleteGooglePending(
                                        now.minus(TemporaryRetention.GOOGLE_PENDING))));
        steps.add(
                new Step(
                        Category.GOOGLE_VERIFICATIONS,
                        () ->
                                purge.deleteGoogleVerifications(
                                        now.minus(TemporaryRetention.GOOGLE_VERIFICATION))));
        steps.add(new Step(Category.RECONNECTIONS, () -> purge.deleteReconnections(now)));
        return steps;
    }
}
