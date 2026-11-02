package fr.emploigo.establishment.application.synchronization;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** The feed with the answer given; records the known publication of each fetch. */
public final class FakeTransitFeedClient implements TransitFeedClient {
    private final List<Optional<Instant>> fetches = new ArrayList<>();
    private Result answer = new Result.Unavailable("aucune réponse prévue");

    public FakeTransitFeedClient answer(Result result) {
        answer = result;
        return this;
    }

    public List<Optional<Instant>> fetches() {
        return fetches;
    }

    @Override
    public Result fetch(Optional<Instant> knownPublication) {
        fetches.add(knownPublication);
        return answer;
    }
}
