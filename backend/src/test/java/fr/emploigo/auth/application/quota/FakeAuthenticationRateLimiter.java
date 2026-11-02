package fr.emploigo.auth.application.quota;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The counters of the actions in memory: every attempt is allowed unless the test chose another
 * answer for its scope, and each counted attempt is recorded.
 */
public final class FakeAuthenticationRateLimiter implements AuthenticationRateLimiter {
    /** An attempt counted under a scope, with its identity and its address when it has one. */
    public record Counted(QuotaScope scope, String identity, String address) {}

    private final Map<QuotaScope, Outcome> answers = new EnumMap<>(QuotaScope.class);
    private final List<Counted> counted = new ArrayList<>();

    public void answer(QuotaScope scope, Outcome outcome) {
        answers.put(scope, outcome);
    }

    public List<Counted> counted() {
        return List.copyOf(counted);
    }

    @Override
    public Outcome check(QuotaScope scope, String identity, String address, QuotaLimits limits) {
        return count(scope, identity, address);
    }

    @Override
    public Outcome checkIdentity(QuotaScope scope, String identity, IdentityLimits limits) {
        return count(scope, identity, null);
    }

    private Outcome count(QuotaScope scope, String identity, String address) {
        counted.add(new Counted(scope, identity, address));
        return answers.getOrDefault(scope, new Allowed());
    }
}
