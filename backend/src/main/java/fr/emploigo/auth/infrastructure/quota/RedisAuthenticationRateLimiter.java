package fr.emploigo.auth.infrastructure.quota;

import fr.emploigo.auth.application.quota.AuthenticationRateLimiter;
import fr.emploigo.auth.application.quota.ClientNetwork;
import fr.emploigo.auth.application.quota.IdentityLimits;
import fr.emploigo.auth.application.quota.LogThrottle;
import fr.emploigo.auth.application.quota.QuotaLimits;
import fr.emploigo.auth.application.quota.QuotaScope;
import fr.emploigo.config.ApiOnly;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The address is counted first, so an attempt it refuses leaves no identity key behind: Redis does
 * not evict, and a client that varies the identity must not fill it. The prefix is counted after
 * the identity and the delay, so it counts only what sends an email. The witness key, created once
 * and never deleted, reports a Redis that lost its data, and the global count of an email action
 * only alerts, it never refuses. The prefix keeps an IPv6 client from spreading over its 60-bit
 * networks to escape the count of its address; counting by /60 bounds the address keys of a /48 at
 * 4096, and the monitoring of Redis covers the identity keys they admit. The script of an action
 * counted by its identity alone runs only once the secret has been verified, so a client cannot
 * create counters by varying it.
 */
@ApiOnly
@Component
public class RedisAuthenticationRateLimiter implements AuthenticationRateLimiter {
    private static final Logger LOG = LoggerFactory.getLogger(RedisAuthenticationRateLimiter.class);

    /**
     * Commands the API account needs on {@code ~auth:limits:*}, scripts included (Redis checks the
     * ACL of each command a script calls); {@code docs/exploitation.md} quotes them and a test
     * checks it.
     */
    public static final String REQUIRED_COMMANDS =
            "+eval +evalsha +incr +expire +exists +pttl +get +set +del +hmget +hset";

    public static final String PREFIX = "auth:limits:";
    public static final String WITNESS_KEY = CounterLossReport.WITNESS_KEY;
    private static final String EXCEEDED = "auth.rate_limit.global.exceeded";
    private static final String PREFIX_LIMITED = "auth.rate_limit.prefix.limited";

    /**
     * The wait of a counter that refuses: what is left of its life, and a life given back to a
     * counter that has none.
     */
    private static final String WAIT =
            """
            local function wait(key)
              local ttl = redis.call('PTTL', key)
              if ttl < 0 then
                redis.call('EXPIRE', key, ARGV[1])
                ttl = tonumber(ARGV[1]) * 1000
              end
              return math.max(ttl, 1)
            end
            """;

    private static final String NO_PREFIX = PREFIX + "no-prefix";

    /**
     * Counters of an address and of an identity, then, for an email, a delay between two sends, the
     * /48 prefix of an IPv6 client and a global count. KEYS: address, identity, global, delay,
     * witness, prefix. ARGV: window and address limit and identity limit, then whether the action
     * sends an email, the delay in seconds and the global alert threshold, then the limit of the
     * prefix (0 when the action has none, or the client has no prefix). The prefix is read at once,
     * to refuse when it is full, and counted only once the send is certain. The delay is tested
     * before the identity, so a request it refuses sends nothing and costs the identity none of its
     * sends. The reply holds the wait, whether the witness was just created, whether the global
     * count is over its alert threshold, whether the prefix refused, and whether the delay between
     * two sends refused.
     */
    private static final DefaultRedisScript<List<Long>> COUNTERS =
            counters(
                    WAIT
                            + """
                            local lost = 0
                            if redis.call('SET', KEYS[5], '1', 'NX') then lost = 1 end
                            local address = redis.call('INCR', KEYS[1])
                            if address == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end
                            if address > tonumber(ARGV[2]) then return {wait(KEYS[1]), lost, 0, 0, 0} end
                            if tonumber(ARGV[7]) > 0 and tonumber(redis.call('GET', KEYS[6]) or '0') >= tonumber(ARGV[7]) then
                              return {wait(KEYS[6]), lost, 0, 1, 0}
                            end
                            if ARGV[4] == '1' and redis.call('EXISTS', KEYS[4]) == 1 then
                              return {wait(KEYS[4]), lost, 0, 0, 1}
                            end
                            local identity = redis.call('INCR', KEYS[2])
                            if identity == 1 then redis.call('EXPIRE', KEYS[2], ARGV[1]) end
                            if identity > tonumber(ARGV[3]) then return {wait(KEYS[2]), lost, 0, 0, 0} end
                            if ARGV[4] == '1' then
                              if tonumber(ARGV[7]) > 0 then
                                if redis.call('INCR', KEYS[6]) == 1 then redis.call('EXPIRE', KEYS[6], ARGV[1]) end
                              end
                              local total = redis.call('INCR', KEYS[3])
                              if total == 1 then redis.call('EXPIRE', KEYS[3], ARGV[1]) end
                              redis.call('SET', KEYS[4], '1', 'EX', ARGV[5])
                              if total > tonumber(ARGV[6]) then return {0, lost, 1, 0, 0} end
                            end
                            return {0, lost, 0, 0, 0}
                            """);

    /**
     * The counter of one key alone, for an identity whose secret is verified. KEYS: counter,
     * witness. ARGV: window and limit. Replies like {@link #COUNTERS}, with no global count, no
     * prefix and no delay between sends.
     */
    private static final DefaultRedisScript<List<Long>> IDENTITY =
            counters(
                    WAIT
                            + """
                            local lost = 0
                            if redis.call('SET', KEYS[2], '1', 'NX') then lost = 1 end
                            local count = redis.call('INCR', KEYS[1])
                            if count == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end
                            if count > tonumber(ARGV[2]) then return {wait(KEYS[1]), lost, 0, 0, 0} end
                            return {0, lost, 0, 0, 0}
                            """);

    private final StringRedisTemplate redis;
    private final QuotaKeys keys;
    private final LogThrottle warnings;
    private final CounterLossReport lossReport;
    private final Map<QuotaScope, Counter> exceeded = new EnumMap<>(QuotaScope.class);
    private final Map<QuotaScope, LogThrottle> exceededWarnings = new EnumMap<>(QuotaScope.class);
    private final Map<QuotaScope, Counter> prefixLimited = new EnumMap<>(QuotaScope.class);
    private final Map<QuotaScope, LogThrottle> prefixWarnings = new EnumMap<>(QuotaScope.class);

    public RedisAuthenticationRateLimiter(
            StringRedisTemplate redis, QuotaKeys keys, Clock clock, MeterRegistry meters) {
        this.redis = redis;
        this.keys = keys;
        this.warnings = new LogThrottle(clock, Duration.ofMinutes(1));
        this.lossReport = new CounterLossReport(LOG, clock, meters);
        // Registered at once, so a counter is visible at zero before the first overrun.
        Arrays.stream(QuotaScope.values())
                .filter(QuotaScope::mail)
                .forEach(
                        scope -> {
                            exceeded.put(
                                    scope,
                                    Counter.builder(EXCEEDED)
                                            .tag("scope", scope.key())
                                            .description(
                                                    "Envois d'email au-delà du seuil d'alerte global de l'action, acceptés malgré tout")
                                            .register(meters));
                            exceededWarnings.put(
                                    scope, new LogThrottle(clock, Duration.ofMinutes(1)));
                            prefixLimited.put(
                                    scope,
                                    Counter.builder(PREFIX_LIMITED)
                                            .tag("scope", scope.key())
                                            .description(
                                                    "Demandes d'email refusées parce que le préfixe /48 IPv6 du client a atteint sa limite")
                                            .register(meters));
                            prefixWarnings.put(
                                    scope, new LogThrottle(clock, Duration.ofMinutes(1)));
                        });
    }

    /** The reply of the script is a list, which the template types by the class of the result. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static DefaultRedisScript<List<Long>> counters(String source) {
        return new DefaultRedisScript<>(source, (Class) List.class);
    }

    @Override
    public Outcome check(QuotaScope scope, String identity, String address, QuotaLimits limits) {
        if (scope.failureDelay())
            throw new IllegalArgumentException(
                    "L'action "
                            + scope.key()
                            + " compte des échecs et suit un délai : "
                            + "elle passe par LoginAttemptLimiter, pas par ce limiteur.");
        if (scope.identityOnly())
            throw new IllegalArgumentException(
                    "L'action "
                            + scope.key()
                            + " n'a pas de limite par adresse : elle "
                            + "passe par checkIdentity.");
        String prefix = PREFIX + scope.key() + ":";
        String hashedIdentity = keys.identity(identity);
        var mail =
                scope.mail()
                        ? Objects.requireNonNull(limits.mail(), "mail limits of " + scope.key())
                        : null;
        // A limit of 0 skips the prefix counter; NO_PREFIX only fills the key slot the script
        // declares.
        var prefix48 = mail == null ? Optional.<String>empty() : ClientNetwork.prefix48(address);
        var reply =
                reply(
                        COUNTERS,
                        List.of(
                                prefix + "ip:" + keys.address(address),
                                prefix + "identity:" + hashedIdentity,
                                PREFIX + scope.key() + ":global",
                                PREFIX + scope.key() + ":cooldown:" + hashedIdentity,
                                WITNESS_KEY,
                                prefix48.map(value -> prefix + "prefix48:" + keys.prefix48(value))
                                        .orElse(NO_PREFIX)),
                        Long.toString(limits.window().toSeconds()),
                        Integer.toString(limits.addressLimit()),
                        Integer.toString(limits.identityLimit()),
                        mail == null ? "0" : "1",
                        mail == null ? "0" : Long.toString(mail.sendDelay().toSeconds()),
                        mail == null ? "0" : Integer.toString(mail.globalAlertThreshold()),
                        mail == null || prefix48.isEmpty()
                                ? "0"
                                : Integer.toString(mail.prefixLimit()));
        if (reply.isEmpty()) return new Unavailable();
        return outcome(scope, reply.get());
    }

    @Override
    public Outcome checkIdentity(QuotaScope scope, String identity, IdentityLimits limits) {
        if (!scope.identityOnly())
            throw new IllegalArgumentException(
                    "L'action "
                            + scope.key()
                            + " a une limite par adresse : elle passe "
                            + "par check.");
        return single(
                PREFIX + scope.key() + ":identity:" + keys.identity(identity),
                scope,
                limits.window(),
                limits.limit());
    }

    private Outcome single(String counter, QuotaScope scope, Duration window, int limit) {
        var reply =
                reply(
                        IDENTITY,
                        List.of(counter, WITNESS_KEY),
                        Long.toString(window.toSeconds()),
                        Integer.toString(limit));
        if (reply.isEmpty()) return new Unavailable();
        return outcome(scope, reply.get());
    }

    /** Runs a script; an error, a delay or an unreadable reply is an unanswered call. */
    private Optional<List<?>> reply(
            DefaultRedisScript<List<Long>> script, List<String> redisKeys, Object... arguments) {
        return RedisCall.run(
                () -> {
                    List<?> raw = redis.execute(script, redisKeys, arguments);
                    if (raw == null
                            || raw.size() != 5
                            || !(raw.get(0) instanceof Number)
                            || !(raw.get(1) instanceof Number)
                            || !(raw.get(2) instanceof Number)
                            || !(raw.get(3) instanceof Number)
                            || !(raw.get(4) instanceof Number))
                        throw new IllegalStateException(
                                "Le script des compteurs n'a pas renvoyé l'attente, l'état du"
                                    + " témoin, le dépassement du seuil, le refus du préfixe et le"
                                    + " délai entre deux envois.");
                    return raw;
                },
                error -> {
                    // Whatever the failure (delay, connection, memory full, script), the counters
                    // did not answer.
                    if (warnings.allow())
                        LOG.warn(
                                "Compteurs de quota indisponibles ({})",
                                CounterLossReport.describe(error));
                });
    }

    private Outcome outcome(QuotaScope scope, List<?> reply) {
        long wait = ((Number) reply.get(0)).longValue();
        boolean lost = ((Number) reply.get(1)).longValue() == 1;
        boolean overThreshold = ((Number) reply.get(2)).longValue() == 1;
        boolean refusedByPrefix = ((Number) reply.get(3)).longValue() == 1;
        boolean refusedBySendDelay = ((Number) reply.get(4)).longValue() == 1;
        // Outside the try: noting a loss or an overrun must never turn an answer of the counters
        // into a refusal.
        if (lost) lossReport.report();
        if (overThreshold) reportOverrun(scope);
        if (refusedByPrefix) reportPrefix(scope);
        if (wait == 0) return new Allowed();
        return new Limited(
                Duration.ofMillis(wait), refusedBySendDelay ? Cause.SEND_DELAY : Cause.VOLUME);
    }

    /** The /48 prefix of the client is at its limit: no identity, address or prefix in the line. */
    private void reportPrefix(QuotaScope scope) {
        prefixLimited.get(scope).increment();
        if (prefixWarnings.get(scope).allow())
            LOG.warn(
                    "Limite du préfixe /48 IPv6 atteinte pour l'action {} : les demandes de ce préfixe sont refusées "
                            + "jusqu'à la fin de la fenêtre. "
                            + "Un faux positif refuse des jeunes qui partagent le préfixe ; à "
                            + "régler par prefix-limit (métrique {}).",
                    scope.key(),
                    PREFIX_LIMITED);
    }

    /**
     * The send went on: the threshold is an alert, not a limit. No identity and no address in the
     * line.
     */
    private void reportOverrun(QuotaScope scope) {
        exceeded.get(scope).increment();
        if (exceededWarnings.get(scope).allow())
            LOG.warn(
                    "Seuil d'alerte global des envois dépassé pour l'action {} : les envois continuent, les limites "
                            + "par adresse et par identité et le délai entre deux envois s'appliquent (métrique {}).",
                    scope.key(),
                    EXCEEDED);
    }
}
