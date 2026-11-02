package fr.emploigo.auth.infrastructure.quota;

import fr.emploigo.auth.application.quota.IdentityLimits;
import fr.emploigo.auth.application.quota.LoginLimits;
import fr.emploigo.auth.application.quota.QuotaLimits;
import fr.emploigo.auth.application.quota.QuotaScope;
import fr.emploigo.config.ApiOnly;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.convert.DurationUnit;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Map;

/**
 * A missing, out-of-bounds or unknown property refuses the start, and a duration without unit is in
 * seconds.
 */
@ApiOnly
@ConfigurationProperties(prefix = "emploi-go.auth.quota", ignoreUnknownFields = false)
public record QuotaProperties(Map<QuotaScope, ScopeQuota> scopes) {
    static final String PREFIX = "emploi-go.auth.quota.";
    static final String VARIABLE_PREFIX = "EMPLOI_GO_AUTH_QUOTA_";
    static final int MAXIMUM_LIMIT = 10_000;
    static final int MAXIMUM_THRESHOLD = 1_000_000;
    static final Duration MINIMUM_WINDOW = Duration.ofMinutes(1);
    static final Duration MAXIMUM_WINDOW = Duration.ofHours(24);
    static final Duration MINIMUM_SEND_DELAY = Duration.ofSeconds(1);
    static final Duration MAXIMUM_SEND_DELAY = Duration.ofHours(1);
    static final int MAXIMUM_FREE_FAILURES = 100;
    static final Duration MINIMUM_BASE_DELAY = Duration.ofSeconds(1);
    static final Duration MAXIMUM_BASE_DELAY = Duration.ofMinutes(5);
    static final double MINIMUM_GROWTH_FACTOR = 1.0;
    static final double MAXIMUM_GROWTH_FACTOR = 10.0;
    static final Duration MAXIMUM_MAX_DELAY = Duration.ofHours(1);
    static final Duration MINIMUM_COUNTER_TTL = Duration.ofMinutes(1);
    static final Duration MAXIMUM_COUNTER_TTL = Duration.ofHours(24);

    /**
     * Caps counter-ttl / window: a client that varies its identity keeps address-limit *
     * counter-ttl / window failure keys alive per network.
     */
    static final int MAXIMUM_COUNTER_TTL_PER_WINDOW = 4;

    public record ScopeQuota(
            Integer addressLimit,
            Integer identityLimit,
            @DurationUnit(ChronoUnit.SECONDS) Duration window,
            @DurationUnit(ChronoUnit.SECONDS) Duration sendDelay,
            Integer globalAlertThreshold,
            Integer prefixLimit,
            FailureDelay failureDelay) {}

    public record FailureDelay(
            Integer freeFailures,
            @DurationUnit(ChronoUnit.SECONDS) Duration baseDelay,
            Double growthFactor,
            @DurationUnit(ChronoUnit.SECONDS) Duration maxDelay,
            @DurationUnit(ChronoUnit.SECONDS) Duration counterTtl) {}

    public QuotaProperties {
        scopes = scopes == null ? Map.of() : Map.copyOf(scopes);
        for (QuotaScope scope : QuotaScope.values()) validate(scope, scopes.get(scope));
    }

    public static String variable(String property) {
        return VARIABLE_PREFIX
                + property.replace('.', '_').replace('-', '_').toUpperCase(Locale.ROOT);
    }

    static String kebab(String field) {
        return field.replaceAll("([a-z])([A-Z])", "$1-$2").toLowerCase(Locale.ROOT);
    }

    private static String property(QuotaScope scope, String field) {
        return PREFIX + "scopes." + scope.key() + "." + field;
    }

    private static void validate(QuotaScope scope, ScopeQuota quota) {
        if (quota == null)
            throw new IllegalStateException(
                    "Le quota "
                            + PREFIX
                            + "scopes."
                            + scope.key()
                            + " est absent : chaque "
                            + "action a ses limites et sa fenêtre.");
        if (scope.identityOnly()) {
            if (quota.addressLimit() != null)
                throw new IllegalStateException(
                        "La propriété "
                                + property(scope, "address-limit")
                                + " n'a pas de "
                                + "sens : l'action "
                                + scope.key()
                                + " n'est pas limitée par adresse (la salle d'une Mission "
                                + "Locale partage la sienne), elle l'est par "
                                + property(scope, "identity-limit")
                                + ".");
        } else {
            number(property(scope, "address-limit"), quota.addressLimit(), 1, MAXIMUM_LIMIT);
        }
        duration(property(scope, "window"), quota.window(), MINIMUM_WINDOW, MAXIMUM_WINDOW);
        if (scope.failureDelay()) {
            if (quota.identityLimit() != null)
                throw new IllegalStateException(
                        "La propriété "
                                + property(scope, "identity-limit")
                                + " n'a pas de "
                                + "sens : l'action "
                                + scope.key()
                                + " ne limite pas les essais d'une identité, elle compte "
                                + "ses échecs et les suit d'un délai ("
                                + property(scope, "failure-delay")
                                + ").");
            failureDelay(scope, quota.failureDelay(), quota.window());
        } else {
            number(property(scope, "identity-limit"), quota.identityLimit(), 1, MAXIMUM_LIMIT);
            if (quota.failureDelay() != null)
                throw new IllegalStateException(
                        "La propriété "
                                + property(scope, "failure-delay")
                                + " n'a pas de "
                                + "sens : l'action "
                                + scope.key()
                                + " ne compte pas d'échecs suivis d'un délai.");
        }
        if (scope.mail()) {
            duration(
                    property(scope, "send-delay"),
                    quota.sendDelay(),
                    MINIMUM_SEND_DELAY,
                    MAXIMUM_SEND_DELAY);
            number(
                    property(scope, "global-alert-threshold"),
                    quota.globalAlertThreshold(),
                    1,
                    MAXIMUM_THRESHOLD);
            number(property(scope, "prefix-limit"), quota.prefixLimit(), 1, MAXIMUM_LIMIT);
            if (quota.prefixLimit() < quota.addressLimit())
                throw new IllegalStateException(
                        "La propriété "
                                + property(scope, "prefix-limit")
                                + " ne peut pas "
                                + "être inférieure à "
                                + property(scope, "address-limit")
                                + " : un préfixe /48 regroupe des "
                                + "réseaux /60, sa limite ne peut pas être plus basse que celle d'un seul.");
        } else {
            notForThisAction(scope, "send-delay", quota.sendDelay());
            notForThisAction(scope, "global-alert-threshold", quota.globalAlertThreshold());
            notForThisAction(scope, "prefix-limit", quota.prefixLimit());
        }
    }

    private static void failureDelay(QuotaScope scope, FailureDelay delay, Duration window) {
        var block = property(scope, "failure-delay");
        if (delay == null)
            throw new IllegalStateException(
                    "Le bloc "
                            + block
                            + " est absent : le délai qui suit les échecs de "
                            + scope.key()
                            + " est obligatoire.");
        number(block + ".free-failures", delay.freeFailures(), 1, MAXIMUM_FREE_FAILURES);
        duration(block + ".base-delay", delay.baseDelay(), MINIMUM_BASE_DELAY, MAXIMUM_BASE_DELAY);
        if (delay.growthFactor() == null)
            throw new IllegalStateException(
                    "La propriété " + block + ".growth-factor est absente.");
        if (!(delay.growthFactor() >= MINIMUM_GROWTH_FACTOR
                && delay.growthFactor() <= MAXIMUM_GROWTH_FACTOR))
            throw new IllegalStateException(
                    "La propriété "
                            + block
                            + ".growth-factor doit rester entre "
                            + MINIMUM_GROWTH_FACTOR
                            + " et "
                            + MAXIMUM_GROWTH_FACTOR
                            + ".");
        duration(block + ".max-delay", delay.maxDelay(), delay.baseDelay(), MAXIMUM_MAX_DELAY);
        // The counter must outlive the longest delay it sets, or a delay would end with the
        // failures that justify it.
        duration(
                block + ".counter-ttl",
                delay.counterTtl(),
                MINIMUM_COUNTER_TTL,
                MAXIMUM_COUNTER_TTL);
        if (delay.counterTtl().compareTo(window.multipliedBy(MAXIMUM_COUNTER_TTL_PER_WINDOW)) > 0)
            throw new IllegalStateException(
                    "La propriété "
                            + block
                            + ".counter-ttl ne peut pas dépasser "
                            + MAXIMUM_COUNTER_TTL_PER_WINDOW
                            + " fois "
                            + property(scope, "window")
                            + " : un client qui "
                            + "varie l'identifiant garderait address-limit × counter-ttl / window clés d'échecs dans Redis.");
        if (delay.counterTtl().compareTo(delay.maxDelay()) <= 0)
            throw new IllegalStateException(
                    "La propriété "
                            + block
                            + ".counter-ttl doit rester plus longue que "
                            + block
                            + ".max-delay : le compteur d'échecs doit survivre au délai qu'il pose.");
    }

    private static void number(String property, Integer value, int minimum, int maximum) {
        if (value == null)
            throw new IllegalStateException("La propriété " + property + " est absente.");
        if (value < minimum || value > maximum)
            throw new IllegalStateException(
                    "La propriété "
                            + property
                            + " doit rester entre "
                            + minimum
                            + " et "
                            + maximum
                            + ".");
    }

    private static void duration(
            String property, Duration value, Duration minimum, Duration maximum) {
        if (value == null)
            throw new IllegalStateException("La propriété " + property + " est absente.");
        if (value.compareTo(minimum) < 0 || value.compareTo(maximum) > 0)
            throw new IllegalStateException(
                    "La propriété "
                            + property
                            + " doit rester entre "
                            + minimum
                            + " et "
                            + maximum
                            + " (durée ISO-8601, ou 15m, 60s ; un nombre seul compte des secondes).");
    }

    private static void notForThisAction(QuotaScope scope, String field, Object value) {
        if (value != null)
            throw new IllegalStateException(
                    "La propriété "
                            + property(scope, field)
                            + " n'a pas de sens : l'action "
                            + scope.key()
                            + " n'envoie pas d'email.");
    }

    public LoginLimits loginLimits() {
        var quota = scopes.get(QuotaScope.LOGIN);
        var delay = quota.failureDelay();
        return new LoginLimits(
                quota.addressLimit(),
                quota.window(),
                new LoginLimits.FailureDelay(
                        delay.freeFailures(),
                        delay.baseDelay(),
                        delay.growthFactor(),
                        delay.maxDelay(),
                        delay.counterTtl()));
    }

    public IdentityLimits identityLimits(QuotaScope scope) {
        if (!scope.identityOnly())
            throw new IllegalArgumentException(
                    "L'action "
                            + scope.key()
                            + " est aussi limitée par adresse : voir "
                            + "limits().");
        var quota = scopes.get(scope);
        return new IdentityLimits(quota.identityLimit(), quota.window());
    }

    public QuotaLimits limits(QuotaScope scope) {
        if (scope.failureDelay())
            throw new IllegalArgumentException(
                    "L'action "
                            + scope.key()
                            + " n'a pas de limite d'identité : voir "
                            + "loginLimits().");
        if (scope.identityOnly())
            throw new IllegalArgumentException(
                    "L'action "
                            + scope.key()
                            + " n'a pas de limite par adresse : voir "
                            + "identityLimits().");
        var quota = scopes.get(scope);
        var mail =
                scope.mail()
                        ? new QuotaLimits.MailLimits(
                                quota.sendDelay(),
                                quota.globalAlertThreshold(),
                                quota.prefixLimit())
                        : null;
        return new QuotaLimits(quota.addressLimit(), quota.identityLimit(), quota.window(), mail);
    }
}
