package fr.emploigo.support;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Collects the events logged by one class while it is open; use it in a try-with-resources so the
 * appender is detached even if the test fails.
 */
public final class LogCapture implements AutoCloseable {
    private final Logger logger;
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    private LogCapture(Logger logger) {
        this.logger = logger;
        appender.start();
        logger.addAppender(appender);
    }

    /** Captures what {@code source} logs at the level its logger lets through. */
    public static LogCapture of(Class<?> source) {
        return new LogCapture((Logger) LoggerFactory.getLogger(source));
    }

    /** The events logged since the capture was opened, oldest first. */
    public List<ILoggingEvent> events() {
        return List.copyOf(appender.list);
    }

    @Override
    public void close() {
        logger.detachAppender(appender);
        appender.stop();
    }
}
