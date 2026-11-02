package fr.emploigo.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.ByteArrayResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

class DevelopmentReadinessTest {
    @TempDir Path directory;

    private final ConfigurableApplicationContext context =
            mock(ConfigurableApplicationContext.class);
    private final ConfigurableApplicationContext childContext =
            mock(ConfigurableApplicationContext.class);
    private final byte[] generation = "old-generation".getBytes(StandardCharsets.UTF_8);

    private Path output() {
        return directory.resolve("ready-api.json");
    }

    private DevelopmentReadiness readiness() throws IOException {
        return new DevelopmentReadiness(
                context, output().toString(), new ByteArrayResource(generation));
    }

    @Test
    void readinessOfAChildContextIsNotAcknowledged() throws IOException {
        var readiness = readiness();

        readiness.ready(event(childContext));

        assertThat(output()).as("readiness file").doesNotExist();
    }

    @Test
    void readinessOfTheOwnContextAcknowledgesTheGenerationCapturedAtCreation() throws IOException {
        var readiness = readiness();
        // A newer publication must not be acknowledged by an older application context.
        System.arraycopy(
                "new-generation".getBytes(StandardCharsets.UTF_8),
                0,
                generation,
                0,
                generation.length);

        readiness.ready(event(context));

        assertThat(Files.readString(output()))
                .as("readiness file")
                .isEqualTo(
                        "{\"generation\":\"old-generation\",\"pid\":"
                                + ProcessHandle.current().pid()
                                + "}");
    }

    @Test
    void readinessLeavesOnlyItsOutputFile() throws IOException {
        var readiness = readiness();

        readiness.ready(event(context));

        try (var files = Files.list(directory)) {
            assertThat(files.toList()).as("files of the directory").containsExactly(output());
        }
    }

    private ApplicationReadyEvent event(ConfigurableApplicationContext source) {
        return new ApplicationReadyEvent(
                new SpringApplication(), new String[0], source, Duration.ZERO);
    }
}
