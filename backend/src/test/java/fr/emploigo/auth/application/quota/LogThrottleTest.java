package fr.emploigo.auth.application.quota;

import static org.assertj.core.api.Assertions.assertThat;

import fr.emploigo.support.TestClock;

import org.junit.jupiter.api.Test;

import java.time.Duration;

class LogThrottleTest {
    private static final Duration INTERVAL = Duration.ofMinutes(1);

    private final TestClock clock = new TestClock();
    private final LogThrottle throttle = new LogThrottle(clock, INTERVAL);

    @Test
    void firstLineIsAllowed() {
        assertThat(throttle.allow()).as("allowed").isTrue();
    }

    @Test
    void secondLineWithinTheIntervalIsSuppressed() {
        throttle.allow();
        clock.now = clock.now.plus(INTERVAL).minusMillis(1);

        assertThat(throttle.allow()).as("allowed").isFalse();
    }

    @Test
    void lineAfterTheIntervalIsAllowed() {
        throttle.allow();
        clock.now = clock.now.plus(INTERVAL);

        assertThat(throttle.allow()).as("allowed").isTrue();
    }

    @Test
    void clockThatWentBackDoesNotSuppressTheLine() {
        clock.now = clock.now.plus(Duration.ofDays(1));
        throttle.allow();
        clock.reset();

        assertThat(throttle.allow()).as("allowed").isTrue();
    }
}
