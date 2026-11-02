package fr.emploigo.establishment.infrastructure.synchronization;

import com.github.kagkarlsson.scheduler.task.helper.RecurringTask;

import fr.emploigo.config.WorkerOnly;
import fr.emploigo.establishment.application.synchronization.EstablishmentRepository;
import fr.emploigo.establishment.application.synchronization.PurgeExcludedEstablishmentsUseCase;
import fr.emploigo.establishment.application.synchronization.SynchronizeEstablishmentsUseCase;
import fr.emploigo.establishment.application.synchronization.TrainingOrganizationRepository;

import io.micrometer.core.instrument.MeterRegistry;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.LocalTime;

/**
 * The daily import of the establishments, and the purge of those excluded for too long; an
 * interrupted run is no failure, and the next one waits for the daily hour. The first import reads
 * the training organisations: it waits for theirs, which starts it.
 */
@Configuration(proxyBeanMethods = false)
@WorkerOnly
@EnableConfigurationProperties(EstablishmentSynchronizationProperties.class)
class EstablishmentSynchronizationConfiguration {
    static final String NAME = "synchronize-establishments";
    static final String PURGE = "purge-excluded-establishments";

    /** Independent of the imports, none of which it waits for. */
    static final LocalTime PURGE_AT = LocalTime.of(5, 30);

    @Bean
    DailySynchronization establishmentSynchronization(
            EstablishmentSynchronizationProperties properties,
            SynchronizeEstablishmentsUseCase synchronization,
            EstablishmentRepository establishments,
            TrainingOrganizationRepository trainers) {
        return DailySynchronization.importing(
                NAME,
                properties.dailyAt(),
                () -> establishments.isEmpty() && trainers.findPublishedAt().isPresent(),
                synchronization::synchronize);
    }

    @Bean
    RecurringTask<Void> excludedEstablishmentsPurge(PurgeExcludedEstablishmentsUseCase purge) {
        return DailySynchronization.task(PURGE, PURGE_AT, purge::purge);
    }

    @Bean
    SireneEstablishmentDirectoryClient establishmentDirectoryClient(
            EstablishmentSynchronizationProperties properties,
            ObjectMapper json,
            MeterRegistry meters,
            Clock clock) {
        return new SireneEstablishmentDirectoryClient(
                properties, json, meters, clock, Thread::sleep);
    }
}
