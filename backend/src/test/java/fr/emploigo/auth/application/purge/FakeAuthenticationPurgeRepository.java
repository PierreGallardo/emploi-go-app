package fr.emploigo.auth.application.purge;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;

/**
 * An in-memory purge: every statement changes nothing and handles no row unless a test drives it
 * by the name of its method in {@link AuthenticationPurgeRepository}.
 */
public final class FakeAuthenticationPurgeRepository implements AuthenticationPurgeRepository {
    private final Map<String, IntSupplier> answers = new HashMap<>();
    private final List<String> statements = new CopyOnWriteArrayList<>();

    /** Makes each batch of {@code statement} handle the rows that {@code batch} answers. */
    public FakeAuthenticationPurgeRepository answering(String statement, IntSupplier batch) {
        answers.put(statement, batch);
        return this;
    }

    /** Makes {@code statement} handle {@code rows} rows, at most {@link #BATCH_SIZE} per batch. */
    public FakeAuthenticationPurgeRepository holding(String statement, int rows) {
        var left = new AtomicInteger(rows);
        return answering(
                statement,
                () -> Math.min(left.getAndUpdate(n -> Math.max(0, n - BATCH_SIZE)), BATCH_SIZE));
    }

    /** The batches of {@code statement} run so far. */
    public long calls(String statement) {
        return statements.stream().filter(statement::equals).count();
    }

    /** The names of the statements run so far, batches included, in order. */
    public List<String> statements() {
        return List.copyOf(statements);
    }

    @Override
    public int expireRegistrations(Instant expiredBy) {
        return statement("expireRegistrations");
    }

    @Override
    public int deleteRegistrations(Instant expiredBefore) {
        return statement("deleteRegistrations");
    }

    @Override
    public int deletePasswordResets(Instant expiredBefore) {
        return statement("deletePasswordResets");
    }

    @Override
    public int deleteGoogleFlows(Instant expiredBefore) {
        return statement("deleteGoogleFlows");
    }

    @Override
    public int deleteGooglePending(Instant expiredBefore) {
        return statement("deleteGooglePending");
    }

    @Override
    public int deleteGoogleVerifications(Instant expiredBefore) {
        return statement("deleteGoogleVerifications");
    }

    @Override
    public int deleteReconnections(Instant expiredBefore) {
        return statement("deleteReconnections");
    }

    private int statement(String name) {
        statements.add(name);
        var answer = answers.get(name);
        return answer == null ? 0 : answer.getAsInt();
    }
}
