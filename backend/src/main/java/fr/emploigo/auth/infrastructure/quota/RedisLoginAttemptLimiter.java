package fr.emploigo.auth.infrastructure.quota;

import fr.emploigo.auth.application.quota.LogThrottle;
import fr.emploigo.auth.application.quota.LoginAttemptLimiter;
import fr.emploigo.auth.application.quota.LoginLimits;
import fr.emploigo.config.ApiOnly;

import io.micrometer.core.instrument.MeterRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

/**
 * Failures are counted before the password is checked, in the same atomic script that tests the
 * delay, so concurrent attempts cannot all pass before one of them fails. A delay in force refuses
 * without counting anything, since it checks no password. A failure is never given back: a success
 * or a recovered password forgets the failures and the delay of the identity in one DEL, and the
 * address counters stay.
 */
@ApiOnly
@Component
public class RedisLoginAttemptLimiter implements LoginAttemptLimiter {
    private static final Logger LOG = LoggerFactory.getLogger(RedisLoginAttemptLimiter.class);
    private static final String PREFIX = RedisAuthenticationRateLimiter.PREFIX;

    /**
     * KEYS: address, failures, delay, witness. ARGV: window and address limit, then free failures,
     * base delay and maximum delay in milliseconds, growth factor, counter ttl in seconds. Replies
     * the wait in milliseconds (0 when the attempt goes on), and 1 when the witness was just
     * created.
     */
    private static final DefaultRedisScript<List<Object>> BEGIN =
            script(
                    """
            local lost = 0
            if redis.call('SET', KEYS[4], '1', 'NX') then lost = 1 end
            local left = redis.call('PTTL', KEYS[3])
            if left > 0 then return {left, lost} end
            local address = redis.call('INCR', KEYS[1])
            if address == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end
            if address > tonumber(ARGV[2]) then
              local ttl = redis.call('PTTL', KEYS[1])
              if ttl < 0 then
                redis.call('EXPIRE', KEYS[1], ARGV[1])
                ttl = tonumber(ARGV[1]) * 1000
              end
              return {math.max(ttl, 1), lost}
            end
            local failures = redis.call('INCR', KEYS[2])
            redis.call('EXPIRE', KEYS[2], ARGV[7])
            if failures >= tonumber(ARGV[3]) then
              local delay = math.min(tonumber(ARGV[6]), tonumber(ARGV[4]) * tonumber(ARGV[5]) ^ (failures - tonumber(ARGV[3])))
              redis.call('SET', KEYS[3], '1', 'PX', math.floor(delay))
            end
            return {0, lost}
            """);

    private final StringRedisTemplate redis;
    private final QuotaKeys keys;
    private final LogThrottle warnings;
    private final CounterLossReport lossReport;

    public RedisLoginAttemptLimiter(
            StringRedisTemplate redis, QuotaKeys keys, Clock clock, MeterRegistry meters) {
        this.redis = redis;
        this.keys = keys;
        this.warnings = new LogThrottle(clock, Duration.ofMinutes(1));
        this.lossReport = new CounterLossReport(LOG, clock, meters);
    }

    /** The reply of the script is a list, which the template types by the class of the result. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static DefaultRedisScript<List<Object>> script(String source) {
        return new DefaultRedisScript<>(source, (Class) List.class);
    }

    private String failuresKey(String identity) {
        return PREFIX + "login:failures:" + keys.identity(identity);
    }

    private String delayKey(String identity) {
        return PREFIX + "login:delay:" + keys.identity(identity);
    }

    @Override
    public Admission begin(String identity, String address, LoginLimits limits) {
        var delay = limits.delay();
        var reply =
                RedisCall.run(
                        () ->
                                readBegin(
                                        redis.execute(
                                                BEGIN,
                                                List.of(
                                                        PREFIX + "ip:" + keys.address(address),
                                                        failuresKey(identity),
                                                        delayKey(identity),
                                                        CounterLossReport.WITNESS_KEY),
                                                Long.toString(limits.window().toSeconds()),
                                                Integer.toString(limits.addressLimit()),
                                                Integer.toString(delay.freeFailures()),
                                                Long.toString(delay.baseDelay().toMillis()),
                                                Double.toString(delay.growthFactor()),
                                                Long.toString(delay.maxDelay().toMillis()),
                                                Long.toString(delay.counterTtl().toSeconds()))),
                        error -> failed("begin", error));
        if (reply.isEmpty()) return new Unavailable();
        var begun = reply.get();
        if (begun.lost()) lossReport.report();
        return begun.waitMillis() == 0
                ? new Admitted()
                : new Limited(Duration.ofMillis(begun.waitMillis()));
    }

    private record Begun(long waitMillis, boolean lost) {}

    private static Begun readBegin(List<Object> reply) {
        if (reply != null
                && reply.size() == 2
                && reply.get(0) instanceof Number waited
                && reply.get(1) instanceof Number lost)
            return new Begun(waited.longValue(), lost.longValue() == 1);
        throw new IllegalStateException(
                "Le script de la connexion n'a pas renvoyé l'attente et l'état du témoin.");
    }

    @Override
    public boolean forget(String identity) {
        return RedisCall.run(
                        () ->
                                read(
                                        redis.delete(
                                                List.of(
                                                        failuresKey(identity),
                                                        delayKey(identity)))),
                        error -> failed("forget", error))
                .isPresent();
    }

    private static Boolean read(Long reply) {
        if (reply == null)
            throw new IllegalStateException("Les compteurs de la connexion n'ont pas répondu.");
        return Boolean.TRUE;
    }

    private void failed(String call, RuntimeException error) {
        // Whatever the failure (delay, connection, memory full, script), the counters did not
        // answer.
        if (warnings.allow())
            LOG.warn(
                    "Compteurs de quota indisponibles pour la connexion, appel {} ({})",
                    call,
                    CounterLossReport.describe(error));
    }
}
