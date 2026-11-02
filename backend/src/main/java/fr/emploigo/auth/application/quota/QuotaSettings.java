package fr.emploigo.auth.application.quota;

import java.util.Map;

/**
 * The limits of every action, built once from the configuration: the use cases read these values,
 * never the configuration itself.
 */
public record QuotaSettings(
        LoginLimits loginLimits,
        Map<QuotaScope, QuotaLimits> limitsByScope,
        Map<QuotaScope, IdentityLimits> identityLimitsByScope) {

    public QuotaSettings {
        limitsByScope = Map.copyOf(limitsByScope);
        identityLimitsByScope = Map.copyOf(identityLimitsByScope);
    }

    /** The limits by address of an action; an action counted by identity alone has none. */
    public QuotaLimits limits(QuotaScope scope) {
        return of(limitsByScope, scope);
    }

    /** The limits by identity of an action counted by identity alone. */
    public IdentityLimits identityLimits(QuotaScope scope) {
        return of(identityLimitsByScope, scope);
    }

    private static <T> T of(Map<QuotaScope, T> values, QuotaScope scope) {
        var value = values.get(scope);
        if (value == null)
            throw new IllegalArgumentException(
                    "Aucun paramètre de quota pour l'action " + scope.key());
        return value;
    }
}
