package fr.emploigo.auth.infrastructure.quota;

import fr.emploigo.auth.application.quota.IdentityLimits;
import fr.emploigo.auth.application.quota.LoginLimits;
import fr.emploigo.auth.application.quota.QuotaLimits;
import fr.emploigo.auth.application.quota.QuotaScope;
import fr.emploigo.auth.application.quota.QuotaSettings;
import fr.emploigo.auth.infrastructure.quota.QuotaProperties.FailureDelay;
import fr.emploigo.auth.infrastructure.quota.QuotaProperties.ScopeQuota;

import java.time.Duration;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.stream.Collectors;

/**
 * Test quotas, with values unlike production and different per scope, so a test proves limits come
 * from the configuration.
 */
public final class QuotaTestProperties {
    private QuotaTestProperties() {}

    /** The test quotas of every scope. */
    public static QuotaProperties of() {
        var scopes =
                Arrays.stream(QuotaScope.values())
                        .collect(
                                Collectors.toMap(
                                        scope -> scope,
                                        scope -> quota(scope),
                                        (a, b) -> a,
                                        () -> new EnumMap<>(QuotaScope.class)));
        return new QuotaProperties(scopes);
    }

    /** The settings of {@link #of()} as the use cases read them. */
    public static QuotaSettings settings() {
        return new QuotaConfiguration().quotaSettings(of());
    }

    public static QuotaLimits limits(QuotaScope scope) {
        return of().limits(scope);
    }

    public static IdentityLimits identityLimits(QuotaScope scope) {
        return of().identityLimits(scope);
    }

    public static LoginLimits loginLimits() {
        return of().loginLimits();
    }

    private static ScopeQuota quota(QuotaScope scope) {
        int rank = scope.ordinal();
        // The login has no identity limit (a failure delay instead); the reconnection has no
        // address limit.
        var delay =
                scope.failureDelay()
                        ? new FailureDelay(
                                4,
                                Duration.ofSeconds(3),
                                3.0,
                                Duration.ofSeconds(90),
                                Duration.ofMinutes(10))
                        : null;
        return new ScopeQuota(
                scope.identityOnly() ? null : 10 + rank,
                scope.failureDelay() ? null : 1 + rank,
                Duration.ofMinutes(5 + rank),
                scope.mail() ? Duration.ofSeconds(30 + rank) : null,
                scope.mail() ? 4 + rank : null,
                scope.mail() ? 50 + rank : null,
                delay);
    }
}
