package fr.emploigo.support;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * Clock the tests move by assigning {@link #now}; {@link IntegrationTest} resets it before every
 * test.
 */
public class TestClock extends Clock {
    public volatile Instant now = Instant.now();

    public void reset() {
        now = Instant.ofEpochMilli(System.currentTimeMillis());
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return now;
    }
}
