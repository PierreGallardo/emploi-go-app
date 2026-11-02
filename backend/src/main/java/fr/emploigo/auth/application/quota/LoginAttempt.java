package fr.emploigo.auth.application.quota;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * An attempt admitted by the counters, which records its success once. A failed or interrupted
 * attempt records nothing: its failure stays counted. Neither method throws: a counter that cannot
 * take the answer is reported, and the response of the login does not change.
 */
public final class LoginAttempt {
    /** The attempt of a login that the counters did not count: its success records nothing. */
    static final LoginAttempt UNTRACKED = new LoginAttempt(null, null, null);

    private final LoginAttemptLimiter limiter;
    private final String identity;
    private final Consumer<Outcome> unrecorded;
    private final AtomicBoolean resolved = new AtomicBoolean();

    /**
     * What became of an identity's failures: a success ({@link #SUCCEEDED}) or a recovered password
     * ({@link #RESET}); also the tag of the metric that counts the answers not recorded.
     */
    enum Outcome {
        SUCCEEDED("succeeded"),
        RESET("reset");

        private final String tag;

        Outcome(String tag) {
            this.tag = tag;
        }

        String tag() {
            return tag;
        }
    }

    LoginAttempt(LoginAttemptLimiter limiter, String identity, Consumer<Outcome> unrecorded) {
        this.limiter = limiter;
        this.identity = identity;
        this.unrecorded = unrecorded;
    }

    /** The password was right: the failures and delay of the identity are forgotten. */
    public void succeed() {
        if (identity == null || !resolved.compareAndSet(false, true)) return;
        boolean recorded;
        try {
            recorded = limiter.forget(identity);
        } catch (RuntimeException error) {
            recorded = false;
        }
        if (!recorded) unrecorded.accept(Outcome.SUCCEEDED);
    }
}
