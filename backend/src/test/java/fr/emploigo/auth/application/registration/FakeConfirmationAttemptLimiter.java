package fr.emploigo.auth.application.registration;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** The attempts of the pages in memory: the test sets the answers and reads back the calls. */
public final class FakeConfirmationAttemptLimiter implements ConfirmationAttemptLimiter {
    private final List<String> failures = new ArrayList<>();
    private final List<Issue> issued = new ArrayList<>();
    private final List<Issue> takenOver = new ArrayList<>();
    private Optional<Duration> wait = Optional.empty();
    private Answer answer = new Answer(State.WRONG, Duration.ZERO);

    /** The answer of every failed attempt from now on. */
    public void answer(State state, Duration delay) {
        answer = new Answer(state, delay);
    }

    /** The wait of every page that asks for a new code from now on. */
    public void blockedFor(Duration delay) {
        wait = Optional.of(delay);
    }

    /** The emails of the failed attempts, in order. */
    public List<String> failures() {
        return List.copyOf(failures);
    }

    /** The codes issued, in order, each with the email and the key of the page that asked. */
    public List<Issue> issued() {
        return List.copyOf(issued);
    }

    @Override
    public Answer fail(String emailKey, String confirmationKey) {
        failures.add(emailKey);
        return answer;
    }

    @Override
    public Optional<Duration> waiting(String emailKey, String confirmationKey) {
        return wait;
    }

    @Override
    public void codeIssued(String emailKey, String confirmationKey) {
        issued.add(new Issue(emailKey, confirmationKey));
    }

    /** The pages that took an email over, in order. */
    public List<Issue> takenOver() {
        return List.copyOf(takenOver);
    }

    @Override
    public void takeOver(String emailKey, String confirmationKey) {
        takenOver.add(new Issue(emailKey, confirmationKey));
    }

    public record Issue(String emailKey, String confirmationKey) {}
}
