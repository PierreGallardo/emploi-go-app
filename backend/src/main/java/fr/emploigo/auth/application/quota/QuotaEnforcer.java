package fr.emploigo.auth.application.quota;

import fr.emploigo.auth.application.failure.AuthenticationFailedException;
import fr.emploigo.auth.application.quota.AuthenticationRateLimiter.Allowed;
import fr.emploigo.auth.application.quota.AuthenticationRateLimiter.Cause;
import fr.emploigo.auth.application.quota.AuthenticationRateLimiter.Limited;
import fr.emploigo.auth.application.quota.AuthenticationRateLimiter.Unavailable;
import fr.emploigo.config.ApiOnly;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;

/**
 * Counts an attempt before it runs, without any transaction (the counters wait for Redis and must
 * hold no connection or lock); when they cannot answer, each action follows its {@link
 * FailureMode}.
 */
@ApiOnly
@Service
public class QuotaEnforcer {
    private static final String METRIC = "auth.rate_limit.unavailable";
    private static final String UNREADABLE = "auth.rate_limit.address.unreadable";
    private static final Duration WARNING_INTERVAL = Duration.ofMinutes(1);
    private static final Logger LOG = LoggerFactory.getLogger(QuotaEnforcer.class);

    private static final String UNRECORDED = "auth.rate_limit.login.unrecorded";

    private final AuthenticationRateLimiter limiter;
    private final LoginAttemptLimiter loginLimiter;
    private final QuotaSettings settings;
    private final Map<LoginAttempt.Outcome, Counter> unrecorded =
            new EnumMap<>(LoginAttempt.Outcome.class);
    private final LogThrottle unrecordedWarnings;
    private final Map<QuotaScope, Counter> refused = new EnumMap<>(QuotaScope.class);
    private final Map<QuotaScope, Counter> executed = new EnumMap<>(QuotaScope.class);
    private final Map<QuotaScope, LogThrottle> warnings = new EnumMap<>(QuotaScope.class);
    private final Counter unreadable;
    private final LogThrottle unreadableWarnings;

    public QuotaEnforcer(
            AuthenticationRateLimiter limiter,
            LoginAttemptLimiter loginLimiter,
            MeterRegistry meters,
            Clock clock,
            QuotaSettings settings) {
        this.limiter = limiter;
        this.loginLimiter = loginLimiter;
        this.settings = settings;
        // Registered at once, so they are visible at zero before the first answer the counters
        // could not take.
        Arrays.stream(LoginAttempt.Outcome.values())
                .forEach(
                        outcome ->
                                unrecorded.put(
                                        outcome,
                                        Counter.builder(UNRECORDED)
                                                .tag("outcome", outcome.tag())
                                                .description(
                                                        "Écritures de connexion que les compteurs n'ont pas pu enregistrer : remise à "
                                                                + "zéro après un succès (succeeded), remise à zéro après une récupération de "
                                                                + "mot de passe (reset)")
                                                .register(meters)));
        this.unrecordedWarnings = new LogThrottle(clock, WARNING_INTERVAL);
        // Registered at once, so it is visible at zero before the first unreadable address.
        this.unreadable =
                Counter.builder(UNREADABLE)
                        .description(
                                "Adresses de client illisibles, comptées telles quelles sans regroupement")
                        .register(meters);
        this.unreadableWarnings = new LogThrottle(clock, WARNING_INTERVAL);
        Arrays.stream(QuotaScope.values())
                .forEach(
                        scope -> {
                            refused.put(scope, counter(meters, scope, "refused"));
                            executed.put(scope, counter(meters, scope, "executed"));
                            warnings.put(scope, new LogThrottle(clock, WARNING_INTERVAL));
                        });
    }

    private static Counter counter(MeterRegistry meters, QuotaScope scope, String outcome) {
        return Counter.builder(METRIC)
                .tag("scope", scope.key())
                .tag("outcome", outcome)
                .description(
                        "Actions dont les compteurs de quota étaient indisponibles : refusées ou exécutées")
                .register(meters);
    }

    /**
     * Begins an attempt of the login for {@code identity}, the identifier as it was typed. The
     * attempt is counted as a failure at once; the caller calls {@link LoginAttempt#succeed()} when
     * the password is right, and nothing otherwise: a failure stays counted.
     *
     * @throws AuthenticationFailedException when the identity is delayed, the address is at its
     *     limit, or the counters cannot answer (the login is never open)
     */
    public LoginAttempt beginLogin(String identity, String address) {
        return switch (loginLimiter.begin(identity, network(address), settings.loginLimits())) {
            case LoginAttemptLimiter.Admitted _ ->
                    new LoginAttempt(loginLimiter, identity, this::unrecorded);
            case LoginAttemptLimiter.Limited limited ->
                    throw AuthenticationFailedException.rateLimited(limited.retryAfter());
            case LoginAttemptLimiter.Unavailable _ -> {
                unavailable(QuotaScope.LOGIN);
                yield LoginAttempt.UNTRACKED;
            }
        };
    }

    /**
     * Forgets the failures and delay of a recovered account under its identities, its username if
     * it has one; never
     * throws, as the recovery is already committed.
     */
    public void resetLogin(String emailKey, String username) {
        resetLogin(emailKey);
        if (username != null) resetLogin(username);
    }

    private void resetLogin(String identity) {
        boolean recorded;
        try {
            recorded = loginLimiter.forget(identity);
        } catch (RuntimeException error) {
            recorded = false;
        }
        if (recorded) return;
        try {
            unrecorded(LoginAttempt.Outcome.RESET);
        } catch (RuntimeException ignored) {
            // The metric registry or the appender failed: the loss is one line less, and the other
            // identity is still reset.
        }
    }

    /**
     * The counters did not take an answer of the login: the response is unchanged, but it is
     * reported.
     */
    private void unrecorded(LoginAttempt.Outcome outcome) {
        unrecorded.get(outcome).increment();
        if (!unrecordedWarnings.allow()) return;
        if (outcome == LoginAttempt.Outcome.RESET)
            LOG.warn(
                    "La remise à zéro des compteurs de connexion après une récupération de mot de"
                        + " passe n'a pas pu être enregistrée : le délai du compte reste en vigueur"
                        + " jusqu'à son terme (métrique {}, {}).",
                    UNRECORDED,
                    outcome.tag());
        else
            LOG.warn(
                    "La remise à zéro des compteurs de connexion après un succès n'a pas pu être "
                            + "enregistrée : les échecs restent comptés jusqu'à leur terme "
                            + "(métrique {}, {}).",
                    UNRECORDED,
                    outcome.tag());
    }

    /** The wait of an identity after an email of this action, before it may get another. */
    public Duration sendDelay(QuotaScope scope) {
        return settings.limits(scope).mail().sendDelay();
    }

    public void check(QuotaScope scope, String identity, String address) {
        count(scope, identity, network(address));
    }

    public void checkAddress(QuotaScope scope, String address) {
        var network = network(address);
        count(scope, network, network);
    }

    /**
     * Counts an identity whose secret is already verified: a wrong secret never reaches this
     * counter.
     */
    public void checkIdentity(QuotaScope scope, String identity) {
        enforce(scope, limiter.checkIdentity(scope, identity, settings.identityLimits(scope)));
    }

    private void count(QuotaScope scope, String identity, String network) {
        enforce(scope, limiter.check(scope, identity, network, settings.limits(scope)));
    }

    private void enforce(QuotaScope scope, AuthenticationRateLimiter.Outcome outcome) {
        switch (outcome) {
            case Allowed _ -> {}
            case Limited limited when limited.cause() == Cause.SEND_DELAY ->
                    throw AuthenticationFailedException.mailRecentlySent(limited.retryAfter());
            case Limited limited ->
                    throw AuthenticationFailedException.rateLimited(limited.retryAfter());
            case Unavailable _ -> unavailable(scope);
        }
    }

    /**
     * An address that is not a literal is counted as it is and reported: behind the documented
     * proxy it never happens, and a forwarded ip:port would give each request its own counter.
     */
    private String network(String address) {
        return ClientNetwork.parse(address)
                .orElseGet(
                        () -> {
                            unreadable.increment();
                            if (unreadableWarnings.allow())
                                LOG.warn(
                                        "Adresse du client illisible : elle est comptée telle quelle, sans regroupement. "
                                                + "La chaîne de proxys est à vérifier (métrique {}).",
                                        UNREADABLE);
                            return address;
                        });
    }

    private void unavailable(QuotaScope scope) {
        if (scope.failureMode() == FailureMode.CLOSED) {
            refused.get(scope).increment();
            throw AuthenticationFailedException.unavailable();
        }
        executed.get(scope).increment();
        if (warnings.get(scope).allow())
            LOG.warn(
                    "Compteurs de quota indisponibles : l'action {} est exécutée sans contrôle",
                    scope.key());
    }
}
