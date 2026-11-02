package fr.emploigo.establishment.application.synchronization;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** The file with the answer given; records the communes and known publication of each fetch. */
public final class FakeAddressFileClient implements AddressFileClient {
    public record Fetch(Set<String> communeCodes, Optional<Instant> knownPublication) {}

    private final List<Fetch> fetches = new ArrayList<>();
    private Result answer = new Result.Unavailable("aucune réponse prévue");

    public FakeAddressFileClient answer(Result result) {
        answer = result;
        return this;
    }

    public List<Fetch> fetches() {
        return fetches;
    }

    @Override
    public Result fetch(Set<String> communeCodes, Optional<Instant> knownPublication) {
        fetches.add(new Fetch(communeCodes, knownPublication));
        return answer;
    }
}
