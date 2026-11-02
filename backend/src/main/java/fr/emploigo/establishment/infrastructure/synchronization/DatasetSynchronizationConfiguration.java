package fr.emploigo.establishment.infrastructure.synchronization;

import com.github.kagkarlsson.scheduler.task.helper.RecurringTask;

import fr.emploigo.config.WorkerOnly;
import fr.emploigo.establishment.application.synchronization.AddressRepository;
import fr.emploigo.establishment.application.synchronization.SynchronizeAddressesUseCase;
import fr.emploigo.establishment.application.synchronization.SynchronizeTrainingOrganizationsUseCase;
import fr.emploigo.establishment.application.synchronization.SynchronizeTransitStopsUseCase;
import fr.emploigo.establishment.application.synchronization.TrainingOrganizationRepository;
import fr.emploigo.establishment.application.synchronization.TransitStopRepository;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * The daily imports of the Divia stops, of the addresses and of the training organisations; the
 * first import of the training organisations starts the first one of the establishments.
 */
@Configuration(proxyBeanMethods = false)
@WorkerOnly
@EnableConfigurationProperties(DatasetSynchronizationProperties.class)
class DatasetSynchronizationConfiguration {
    static final String TRANSIT_STOPS = "synchronize-transit-stops";
    static final String ADDRESSES = "synchronize-addresses";
    static final String TRAINING_ORGANIZATIONS = "synchronize-training-organizations";
    // About three times the largest file of today: 36 MB for the training organisations.
    static final int MAX_FILE_BYTES = 128 * 1024 * 1024;
    // About three times the 150 000 training organisations of today.
    static final UnpackedLimits TRAINING_ORGANIZATION_LIMITS =
            new UnpackedLimits(MAX_FILE_BYTES, 64 * 1024, 500_000);

    @Bean
    RecurringTask<Void> transitStopSynchronization(
            DatasetSynchronizationProperties properties,
            SynchronizeTransitStopsUseCase synchronization,
            TransitStopRepository stops) {
        return DailySynchronization.importing(
                TRANSIT_STOPS,
                properties.transitStops().dailyAt(),
                () -> stops.findPublishedAt().isEmpty(),
                synchronization::synchronize);
    }

    @Bean
    RecurringTask<Void> addressSynchronization(
            DatasetSynchronizationProperties properties,
            SynchronizeAddressesUseCase synchronization,
            AddressRepository addresses) {
        return DailySynchronization.importing(
                ADDRESSES,
                properties.addresses().dailyAt(),
                () -> addresses.findPublishedAt().isEmpty(),
                synchronization::synchronize);
    }

    @Bean
    RecurringTask<Void> trainingOrganizationSynchronization(
            DatasetSynchronizationProperties properties,
            SynchronizeTrainingOrganizationsUseCase synchronization,
            TrainingOrganizationRepository trainers,
            DailySynchronization establishmentSynchronization) {
        return DailySynchronization.importing(
                        TRAINING_ORGANIZATIONS,
                        properties.trainingOrganizations().dailyAt(),
                        () -> trainers.findPublishedAt().isEmpty(),
                        synchronization::synchronize)
                .then(establishmentSynchronization);
    }

    @Bean
    GtfsTransitFeedClient transitFeedClient(
            DatasetSynchronizationProperties properties, Clock clock) {
        return new GtfsTransitFeedClient(
                properties.transitStops().url(),
                downloader(properties, clock),
                UnpackedLimits.OPEN_DATA);
    }

    @Bean
    BanAddressFileClient addressFileClient(
            DatasetSynchronizationProperties properties, Clock clock) {
        return new BanAddressFileClient(
                properties.addresses().url(),
                downloader(properties, clock),
                UnpackedLimits.OPEN_DATA);
    }

    @Bean
    PublicTrainingOrganizationFileClient trainingOrganizationFileClient(
            DatasetSynchronizationProperties properties, Clock clock) {
        return new PublicTrainingOrganizationFileClient(
                properties.trainingOrganizations().url(),
                downloader(properties, clock),
                TRAINING_ORGANIZATION_LIMITS);
    }

    private static FileDownloader downloader(
            DatasetSynchronizationProperties properties, Clock clock) {
        return new FileDownloader(
                properties.connectTimeout(), properties.downloadTimeout(), MAX_FILE_BYTES, clock);
    }
}
