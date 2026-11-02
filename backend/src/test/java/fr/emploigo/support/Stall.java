package fr.emploigo.support;

import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Holds back a fake provider's responses to simulate a slow provider without sleeping: closing the
 * hold (in a try-with-resources) releases them, and a request nobody releases fails after {@link
 * #GATE_SECONDS}.
 */
public final class Stall {
    public static final int GATE_SECONDS = 5;

    private volatile Hold current;

    public Hold hold() {
        if (current != null)
            throw new IllegalStateException(
                    "Une réponse est déjà retenue : fermer sa retenue d'abord.");
        var hold = new Hold();
        current = hold;
        return hold;
    }

    /** Called by the fake provider once it has handled a request, just before it answers. */
    void pass() throws IOException {
        var hold = current;
        if (hold != null) hold.pass();
    }

    void reset() {
        var hold = current;
        if (hold != null) hold.close();
    }

    public final class Hold implements AutoCloseable {
        private final AtomicInteger requests = new AtomicInteger();
        private final CountDownLatch released = new CountDownLatch(1);
        private volatile boolean expired;

        private Hold() {}

        private void pass() throws IOException {
            requests.incrementAndGet();
            try {
                if (!released.await(GATE_SECONDS, TimeUnit.SECONDS)) {
                    expired = true;
                    throw new IOException("Réponse retenue non libérée.");
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IOException("Réponse retenue interrompue.");
            }
        }

        public void awaitRequests(int count) {
            await().alias(count + " requête(s) retenue(s) par le fournisseur")
                    .atMost(Duration.ofSeconds(GATE_SECONDS))
                    .pollInterval(Duration.ofMillis(10))
                    .until(() -> requests.get() >= count);
        }

        /** Requests that reached the provider while this hold was active. */
        public int requests() {
            return requests.get();
        }

        /**
         * Whether a request was held until the limit of {@link Stall#GATE_SECONDS} instead of being
         * released.
         */
        public boolean expired() {
            return expired;
        }

        /** Sends the held responses. Closing the hold does the same. */
        public void release() {
            released.countDown();
        }

        @Override
        public void close() {
            release();
            if (current == this) current = null;
        }
    }
}
