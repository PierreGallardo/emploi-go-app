package fr.emploigo.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

class DevelopmentSessionWatchTest {
    private final AtomicInteger exits = new AtomicInteger();

    @Test
    void theJvmKeepsRunningWhileTheDevSessionIsAlive() {
        new DevelopmentSessionWatch(() -> true, exits::incrementAndGet).check();

        assertThat(exits).as("exits").hasValue(0);
    }

    @Test
    void theJvmStopsOnceTheDevSessionIsGone() {
        new DevelopmentSessionWatch(() -> false, exits::incrementAndGet).check();

        assertThat(exits).as("exits").hasValue(1);
    }

    @Test
    void aRunningSessionIsAlive() {
        var alive = DevelopmentSessionWatch.sessionAlive(ProcessHandle.current().pid());

        assertThat(alive.getAsBoolean()).as("current process alive").isTrue();
    }

    @Test
    void anEndedSessionIsGone() throws IOException, InterruptedException {
        var java = ProcessHandle.current().info().command().orElseThrow();
        var ended =
                new ProcessBuilder(java, "-version")
                        .redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .start();
        ended.waitFor();

        var alive = DevelopmentSessionWatch.sessionAlive(ended.pid());

        assertThat(alive.getAsBoolean()).as("ended process alive").isFalse();
    }
}
