package fr.emploigo.auth.infrastructure.registration;

import fr.emploigo.auth.application.quota.LogThrottle;
import fr.emploigo.auth.application.registration.ConfirmationAttemptLimiter;
import fr.emploigo.auth.domain.RegistrationCode;
import fr.emploigo.auth.infrastructure.quota.CounterLossReport;
import fr.emploigo.auth.infrastructure.quota.QuotaKeys;
import fr.emploigo.auth.infrastructure.quota.RedisAuthenticationRateLimiter;
import fr.emploigo.auth.infrastructure.quota.RedisCall;
import fr.emploigo.config.ApiOnly;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * The counters of {@link ConfirmationAttemptLimiter} in Redis. The script of a failed attempt
 * reads and counts in one call, so that the answer is the one of the count it made. The counters
 * do not bound a guess: a key that is not the registration's can never confirm. Each script
 * touches only the keys it is given in KEYS, as Redis requires of a script.
 */
@Component
@ApiOnly
public class RedisConfirmationAttemptLimiter implements ConfirmationAttemptLimiter {
    private static final Logger LOG =
            LoggerFactory.getLogger(RedisConfirmationAttemptLimiter.class);
    private static final String PREFIX = RedisAuthenticationRateLimiter.PREFIX + "confirm:";
    private static final long TTL_SECONDS = Duration.ofHours(24).toSeconds();

    /**
     * KEYS: epoch of the page (the time its last code was issued), attempts of the page (a hash of
     * the epoch they count for and their count), failures of the page in total, the hour without
     * attempts of the page, the page that holds the email, then the same four counters of the
     * email. ARGV: attempts per code, failures per hour without attempts, seconds of that hour,
     * seconds of life of a counter, the time in milliseconds, the life of a code in milliseconds,
     * the page. Attempts counted for another epoch are those of an older code: the count starts
     * over. The page that holds the email counts on the email, whose code is the one of the
     * registration it stands for. Replies {state, wait in ms}: 0 wrong code, 1 attempts spent, 2
     * waiting, 3 expired. The order is the one of the database: waiting, expired, spent, then the
     * count.
     */
    private static final DefaultRedisScript<List<Long>> FAIL =
            script(
                    """
                    local base = 0
                    if redis.call('GET', KEYS[5]) == ARGV[7] then base = 5 end
                    local epochKey, attemptsKey = KEYS[1 + base], KEYS[2 + base]
                    local failures, hour = KEYS[3 + base], KEYS[4 + base]
                    local wait = redis.call('PTTL', hour)
                    if wait > 0 then return {2, wait} end
                    local epoch = redis.call('GET', epochKey) or '0'
                    if epoch ~= '0' and tonumber(ARGV[5]) - tonumber(epoch) >= tonumber(ARGV[6]) then
                      return {3, 0}
                    end
                    local last = redis.call('HMGET', attemptsKey, 'epoch', 'count')
                    local count = 0
                    if last[1] == epoch then count = tonumber(last[2]) end
                    if count >= tonumber(ARGV[1]) then return {1, 0} end
                    count = count + 1
                    redis.call('HSET', attemptsKey, 'epoch', epoch, 'count', count)
                    redis.call('EXPIRE', attemptsKey, ARGV[4])
                    local total = redis.call('INCR', failures)
                    redis.call('EXPIRE', failures, ARGV[4])
                    if total % tonumber(ARGV[2]) == 0 then
                      redis.call('SET', hour, '1', 'EX', ARGV[3])
                      redis.call('HSET', attemptsKey, 'count', ARGV[1])
                      return {2, tonumber(ARGV[3]) * 1000}
                    end
                    if count >= tonumber(ARGV[1]) then return {1, 0} end
                    return {0, 0}
                    """);

    /**
     * KEYS: epoch of the page, the page that holds the email, epoch of the email, failures of the
     * email, the hour of the email. ARGV: the time in milliseconds, seconds of life. A code issued
     * for an email that a page holds, outside its hour, is a new code of the email whatever the
     * page that asked, as a new code replaces the one of a registration; it extends the counters of
     * the email as a code extends a registration. Two codes issued in the same millisecond still
     * start the attempts over: an epoch only grows.
     */
    private static final DefaultRedisScript<Long> ISSUED =
            new DefaultRedisScript<>(
                    """
                    local function issue(key)
                      local issued = tonumber(ARGV[1])
                      local last = tonumber(redis.call('GET', key) or '0')
                      if issued <= last then issued = last + 1 end
                      redis.call('SET', key, tostring(issued), 'EX', ARGV[2])
                    end
                    issue(KEYS[1])
                    if redis.call('EXISTS', KEYS[2]) == 1 and redis.call('PTTL', KEYS[5]) <= 0 then
                      issue(KEYS[3])
                      redis.call('EXPIRE', KEYS[2], ARGV[2])
                      redis.call('EXPIRE', KEYS[4], ARGV[2])
                    end
                    return 1
                    """,
                    Long.class);

    /** KEYS: the page that holds the email, the hour of the page, the hour of the email. */
    private static final DefaultRedisScript<Long> WAIT =
            new DefaultRedisScript<>(
                    """
                    if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('PTTL', KEYS[3]) end
                    return redis.call('PTTL', KEYS[2])
                    """,
                    Long.class);

    /**
     * KEYS: the page that holds the email, the hour of the email. ARGV: the page, seconds of life.
     * During the hour, another page takes nothing over, as with a registration in its hour.
     */
    private static final DefaultRedisScript<Long> TAKE_OVER =
            new DefaultRedisScript<>(
                    """
                    if redis.call('PTTL', KEYS[2]) > 0 then return 0 end
                    redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[2])
                    return 1
                    """,
                    Long.class);

    private final StringRedisTemplate redis;
    private final QuotaKeys keys;
    private final Clock clock;
    private final LogThrottle warnings;

    public RedisConfirmationAttemptLimiter(StringRedisTemplate redis, QuotaKeys keys, Clock clock) {
        this.redis = redis;
        this.keys = keys;
        this.clock = clock;
        this.warnings = new LogThrottle(clock, Duration.ofMinutes(1));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static DefaultRedisScript<List<Long>> script(String source) {
        return new DefaultRedisScript<>(source, (Class) List.class);
    }

    @Override
    public Answer fail(String emailKey, String confirmationKey) {
        String page = keys.confirmationPage(emailKey, confirmationKey);
        var reply =
                RedisCall.run(
                        () ->
                                redis.execute(
                                        FAIL,
                                        List.of(
                                                epochKey(page),
                                                PREFIX + "attempts:" + page,
                                                PREFIX + "failures:" + page,
                                                PREFIX + "wait:" + page,
                                                holderKey(emailKey),
                                                PREFIX + "email-epoch:" + email(emailKey),
                                                PREFIX + "email-attempts:" + email(emailKey),
                                                PREFIX + "email-failures:" + email(emailKey),
                                                PREFIX + "email-wait:" + email(emailKey)),
                                        Integer.toString(RegistrationCode.MAX_ATTEMPTS),
                                        Integer.toString(RegistrationCode.FAILURES_PER_BLOCK),
                                        Long.toString(RegistrationCode.BLOCK.toSeconds()),
                                        Long.toString(TTL_SECONDS),
                                        Long.toString(clock.millis()),
                                        Long.toString(RegistrationCode.VALIDITY.toMillis()),
                                        page),
                        this::unavailable);
        if (reply.isEmpty() || reply.get().size() != 2)
            return new Answer(State.UNAVAILABLE, Duration.ZERO);
        long wait = ((Number) reply.get().get(1)).longValue();
        return switch (((Number) reply.get().get(0)).intValue()) {
            case 1 -> new Answer(State.SPENT, Duration.ZERO);
            case 2 -> new Answer(State.WAITING, Duration.ofMillis(Math.max(wait, 1)));
            case 3 -> new Answer(State.EXPIRED, Duration.ZERO);
            default -> new Answer(State.WRONG, Duration.ZERO);
        };
    }

    @Override
    public Optional<Duration> waiting(String emailKey, String confirmationKey) {
        String page = keys.confirmationPage(emailKey, confirmationKey);
        var wait =
                RedisCall.run(
                        () ->
                                redis.execute(
                                        WAIT,
                                        List.of(
                                                holderKey(emailKey),
                                                PREFIX + "wait:" + page,
                                                PREFIX + "email-wait:" + email(emailKey)),
                                        page),
                        this::unavailable);
        return wait.filter(millis -> millis != null && millis > 0).map(Duration::ofMillis);
    }

    @Override
    public void takeOver(String emailKey, String confirmationKey) {
        String page = keys.confirmationPage(emailKey, confirmationKey);
        RedisCall.run(
                () ->
                        redis.execute(
                                TAKE_OVER,
                                List.of(
                                        holderKey(emailKey),
                                        PREFIX + "email-wait:" + email(emailKey)),
                                page,
                                Long.toString(TTL_SECONDS)),
                this::unavailable);
    }

    @Override
    public void codeIssued(String emailKey, String confirmationKey) {
        String page = keys.confirmationPage(emailKey, confirmationKey);
        RedisCall.run(
                () ->
                        redis.execute(
                                ISSUED,
                                List.of(
                                        epochKey(page),
                                        holderKey(emailKey),
                                        PREFIX + "email-epoch:" + email(emailKey),
                                        PREFIX + "email-failures:" + email(emailKey),
                                        PREFIX + "email-wait:" + email(emailKey)),
                                Long.toString(clock.millis()),
                                Long.toString(TTL_SECONDS)),
                this::unavailable);
    }

    private void unavailable(RuntimeException error) {
        if (warnings.allow())
            LOG.warn(
                    "Compteurs de confirmation indisponibles ({}) : les essais d'une page sans"
                            + " inscription ne sont plus comptés.",
                    CounterLossReport.describe(error));
    }

    private static String epochKey(String page) {
        return PREFIX + "epoch:" + page;
    }

    private String holderKey(String emailKey) {
        return PREFIX + "holder:" + email(emailKey);
    }

    private String email(String emailKey) {
        return keys.identity(emailKey);
    }
}
