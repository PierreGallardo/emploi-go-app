package fr.emploigo.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

@Component
@ApiAndWorker
@Profile("local")
@ConditionalOnProperty(name = "emploigo.dev.ready-file")
public class DevelopmentReadiness {
    private final ApplicationContext context;
    private final Path readyFile;
    private final String generation;

    public DevelopmentReadiness(
            ApplicationContext context,
            @Value("${emploigo.dev.ready-file}") String readyFile,
            @Value("classpath:emploi-go-dev-generation.txt") Resource generationResource)
            throws IOException {
        this.context = context;
        this.readyFile = Path.of(readyFile);
        try (var stream = generationResource.getInputStream()) {
            this.generation = new String(stream.readAllBytes(), StandardCharsets.UTF_8).trim();
        }
    }

    @EventListener
    public void ready(ApplicationReadyEvent event) throws IOException {
        if (event.getApplicationContext() != context) return;
        Files.createDirectories(readyFile.getParent());
        Path temporary = Files.createTempFile(readyFile.getParent(), "readiness-", ".tmp");
        try {
            Files.writeString(
                    temporary,
                    "{\"generation\":\""
                            + generation
                            + "\",\"pid\":"
                            + ProcessHandle.current().pid()
                            + "}");
            try {
                Files.move(
                        temporary,
                        readyFile,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, readyFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
