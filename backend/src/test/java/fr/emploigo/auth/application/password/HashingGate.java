package fr.emploigo.auth.application.password;

import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doAnswer;

import org.mockito.invocation.InvocationOnMock;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

/**
 * Holds the verifications of passwords starting with {@link #HELD} inside the encoder until {@link
 * #release()} or {@link #close()}, so a test controls how many {@link PasswordHasher} computations
 * are in progress; a held one that nobody releases ends after {@link #GATE_SECONDS}, so a failed
 * assertion cannot leave a latch closed for other tests.
 */
public final class HashingGate implements AutoCloseable {
    /** Prefix of a password whose verification the gate holds. */
    public static final String HELD = "held:";

    public static final int GATE_SECONDS = 10;

    private final PasswordHasher hashing;
    private final CountDownLatch released = new CountDownLatch(1);
    private final AtomicInteger running = new AtomicInteger();
    private final AtomicInteger peak = new AtomicInteger();
    private final ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
    private final List<Future<?>> saturating = new CopyOnWriteArrayList<>();

    public HashingGate(PasswordHasher hashing, PasswordEncoder encoder) {
        this.hashing = hashing;
        doAnswer(this::hold).when(encoder).matches(startsWith(HELD), anyString());
    }

    private Object hold(InvocationOnMock call) throws InterruptedException {
        peak.accumulateAndGet(running.incrementAndGet(), Math::max);
        try {
            released.await(GATE_SECONDS, TimeUnit.SECONDS);
            return false;
        } finally {
            running.decrementAndGet();
        }
    }

    /**
     * Starts {@code count} verifications and returns once exactly that many are held in the
     * encoder.
     */
    public void saturate(int count) {
        IntStream.range(0, count)
                .forEach(
                        number ->
                                saturating.add(
                                        pool.submit(
                                                () ->
                                                        hashing.matches(
                                                                HELD + "saturation " + number,
                                                                "{pbkdf2-sha256}held"))));
        awaitRunning(count);
    }

    /** Runs a request on a thread of the gate, which stops it when the gate is closed. */
    public <T> Future<T> submit(Callable<T> task) {
        return pool.submit(task);
    }

    public void awaitRunning(int count) {
        await().alias(count + " verification(s) held in the encoder")
                .atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(10))
                .until(() -> running.get() == count);
    }

    public int running() {
        return running.get();
    }

    /** The largest number of verifications held at the same time since the gate was created. */
    public int peak() {
        return peak.get();
    }

    public void release() {
        released.countDown();
    }

    /**
     * Like {@link #release()}, but waits until each verification of {@link #saturate} has returned
     * its permit to {@link PasswordHasher}: {@link #running()} drops before that.
     */
    public void releaseAndWait() {
        release();
        saturating.forEach(
                future -> {
                    try {
                        future.get(GATE_SECONDS, TimeUnit.SECONDS);
                    } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(failure);
                    } catch (ExecutionException | TimeoutException failure) {
                        throw new IllegalStateException(failure);
                    }
                });
    }

    @Override
    public void close() {
        release();
        pool.close();
    }
}
