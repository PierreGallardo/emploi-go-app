package fr.emploigo.config;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Stops a JVM started by {@code npm run dev} once that session is gone, however it ended. */
@Component
@ApiAndWorker
@Profile("local")
@ConditionalOnProperty(name = "emploigo.dev.session-pid")
public class DevelopmentSessionWatch {
    private final BooleanSupplier sessionAlive;
    private final Runnable exit;
    private final ScheduledExecutorService executor =
            Executors.newSingleThreadScheduledExecutor(
                    Thread.ofPlatform().daemon().name("dev-session-watch").factory());

    @Autowired
    public DevelopmentSessionWatch(@Value("${emploigo.dev.session-pid}") long sessionPid) {
        this(sessionAlive(sessionPid), () -> System.exit(0));
    }

    DevelopmentSessionWatch(BooleanSupplier sessionAlive, Runnable exit) {
        this.sessionAlive = sessionAlive;
        this.exit = exit;
    }

    @PostConstruct
    void start() {
        executor.scheduleWithFixedDelay(this::check, 1, 1, TimeUnit.SECONDS);
    }

    void check() {
        if (!sessionAlive.getAsBoolean()) exit.run();
    }

    @PreDestroy
    void stop() {
        executor.shutdownNow();
    }

    static BooleanSupplier sessionAlive(long pid) {
        // The handle keeps the process start time: a reused PID does not pass for the session.
        var session = ProcessHandle.of(pid);
        return () -> session.map(ProcessHandle::isAlive).orElse(false);
    }
}
