package fr.emploigo.establishment.application.synchronization;

import fr.emploigo.config.WorkerOnly;
import fr.emploigo.establishment.application.synchronization.AddressFileClient.Result;
import fr.emploigo.establishment.domain.Street;

import io.micrometer.core.instrument.MeterRegistry;

import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;
import java.util.Set;

/**
 * Replaces the streets of the communes of the metropolis by those of the Base Adresse Nationale,
 * once its file is published again.
 */
@Service
@WorkerOnly
public class SynchronizeAddressesUseCase {
    private static final String DATASET = "addresses";

    private final AddressFileClient file;
    private final AddressRepository addresses;
    private final CommuneRepository communes;
    private final Clock clock;
    private final ImportRuns<DatasetSynchronization> runs;

    public SynchronizeAddressesUseCase(
            AddressFileClient file,
            AddressRepository addresses,
            CommuneRepository communes,
            Clock clock,
            MeterRegistry meters) {
        this.file = file;
        this.addresses = addresses;
        this.communes = communes;
        this.clock = clock;
        this.runs = ImportRuns.ofDataset(DATASET, "voies", meters);
    }

    /**
     * Throws when the file cannot be read or has no street of the metropolis, keeping those in
     * place.
     */
    public DatasetSynchronization synchronize() {
        return runs.record(
                () ->
                        switch (file.fetch(
                                Set.copyOf(communes.findCodes()), addresses.findPublishedAt())) {
                            case Result.Unchanged unchanged -> DatasetSynchronization.unchanged();
                            case Result.Unavailable unavailable ->
                                    runs.unavailable(unavailable.cause());
                            case Result.Published published -> replace(published);
                        });
    }

    private DatasetSynchronization replace(Result.Published published) {
        List<Street> streets = Street.group(published.addresses());
        if (streets.isEmpty())
            throw new DatasetUnavailableException(DATASET, "aucune adresse de la métropole");
        addresses.replace(streets, published.publishedAt(), clock.instant());
        return DatasetSynchronization.updated(streets.size());
    }
}
