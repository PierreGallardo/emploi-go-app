package fr.emploigo.auth.application.password;

import fr.emploigo.config.ApiOnly;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.convert.ConversionException;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

/**
 * The only way to compute a password hash: PBKDF2 is deliberately expensive, so at most {@code
 * limit} computations run at once (the number of cores by default), beyond which the call is
 * refused with {@link PasswordHashingBusyException}; the limit is per process, and a permit is held only
 * while the encoder runs.
 */
@Service
@ApiOnly
public class PasswordHasher {
    static final String PROPERTY = "emploi-go.auth.password-hashing-max-concurrency";
    private static final int MINIMUM = 1;
    private static final int MAXIMUM = 256;
    private static final String REJECTIONS = "auth.password.hashing.rejections";
    private static final Logger LOG = LoggerFactory.getLogger(PasswordHasher.class);

    private final PasswordEncoder encoder;
    private final int limit;
    private final Semaphore permits;
    private final Counter matchesRejections;
    private final Counter encodeRejections;

    public PasswordHasher(PasswordEncoder encoder, MeterRegistry meters, Environment environment) {
        this.encoder = encoder;
        this.limit = read(environment);
        this.permits = new Semaphore(limit);
        this.matchesRejections = rejections(meters, "matches");
        this.encodeRejections = rejections(meters, "encode");
        LOG.info("Calculs d'empreinte de mot de passe simultanés : {} au plus", limit);
    }

    private static int read(Environment environment) {
        Integer value;
        try {
            value = environment.getProperty(PROPERTY, Integer.class);
        } catch (ConversionException error) {
            throw new IllegalStateException(
                    "Le plafond " + PROPERTY + " n'est pas un nombre entier.", error);
        }
        int limit = value == null ? Runtime.getRuntime().availableProcessors() : value;
        if (limit < MINIMUM || limit > MAXIMUM)
            throw new IllegalStateException(
                    "Le plafond "
                            + PROPERTY
                            + " doit rester entre "
                            + MINIMUM
                            + " et "
                            + MAXIMUM
                            + ".");
        return limit;
    }

    private static Counter rejections(MeterRegistry meters, String operation) {
        return Counter.builder(REJECTIONS)
                .tag("operation", operation)
                .description(
                        "Calculs d'empreinte refusés parce que le plafond des calculs simultanés était atteint")
                .register(meters);
    }

    public int limit() {
        return limit;
    }

    /**
     * @throws PasswordHashingBusyException when the limit is reached; nothing was computed
     */
    public boolean matches(String raw, String storedHash) {
        return compute(matchesRejections, () -> encoder.matches(raw, storedHash));
    }

    /**
     * @throws PasswordHashingBusyException when the limit is reached; nothing was computed
     */
    public String encode(String raw) {
        return compute(encodeRejections, () -> encoder.encode(raw));
    }

    /**
     * Refuses at once when no permit is free, so a caller stops before the counters of its quota. It
     * is a snapshot: it takes no permit, since holding one over a network call would let a slow
     * call starve the limit, so {@code encode} can still refuse afterwards.
     *
     * @throws PasswordHashingBusyException when every permit is taken
     */
    public void requireCapacity() {
        if (permits.availablePermits() == 0) {
            encodeRejections.increment();
            throw new PasswordHashingBusyException();
        }
    }

    private <T> T compute(Counter rejections, Supplier<T> computation) {
        if (!permits.tryAcquire()) {
            rejections.increment();
            throw new PasswordHashingBusyException();
        }
        try {
            return computation.get();
        } finally {
            permits.release();
        }
    }
}
