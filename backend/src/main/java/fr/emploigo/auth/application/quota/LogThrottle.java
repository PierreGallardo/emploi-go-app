package fr.emploigo.auth.application.quota;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Lets one log line through per interval, so a condition repeated on every request does not flood
 * the logs; a clock that goes back never silences a line for longer than one interval.
 */
public final class LogThrottle {
    private final Clock clock;
    private final Duration interval;
    private final AtomicReference<Instant> last = new AtomicReference<>();

    public LogThrottle(Clock clock, Duration interval) {
        this.clock = clock;
        this.interval = interval;
    }

    /**
     * Whether the caller may write its line now; only one of several concurrent callers gets {@code
     * true}.
     */
    public boolean allow() {
        Instant now = clock.instant();
        Instant previous = last.get();
        boolean due =
                previous == null
                        || now.isBefore(previous)
                        || !now.isBefore(previous.plus(interval));
        return due && last.compareAndSet(previous, now);
    }
}
