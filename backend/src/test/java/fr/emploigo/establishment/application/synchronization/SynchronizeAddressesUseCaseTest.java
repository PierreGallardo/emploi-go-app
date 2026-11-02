package fr.emploigo.establishment.application.synchronization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import fr.emploigo.establishment.application.synchronization.AddressFileClient.Result;
import fr.emploigo.establishment.application.synchronization.DatasetSynchronization.Outcome;
import fr.emploigo.establishment.domain.Address;
import fr.emploigo.establishment.domain.HouseNumber;
import fr.emploigo.establishment.domain.Position;
import fr.emploigo.establishment.domain.Street;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;

class SynchronizeAddressesUseCaseTest {
    private static final Instant NOW = Instant.parse("2026-10-25T03:10:00Z");
    private static final Instant PUBLISHED = Instant.parse("2026-10-23T05:01:54Z");
    private static final Address ADDRESS =
            new Address(
                    "21231", "Rue Fictive", new HouseNumber(1, null, new Position(5.04, 47.32)));

    private final FakeAddressFileClient file = new FakeAddressFileClient();
    private final FakeAddressRepository addresses = new FakeAddressRepository();
    private final SynchronizeAddressesUseCase synchronization =
            new SynchronizeAddressesUseCase(
                    file,
                    addresses,
                    () -> List.of("21231", "21617"),
                    Clock.fixed(NOW, ZoneOffset.UTC),
                    new SimpleMeterRegistry());

    @AfterEach
    void clearTheInterruption() {
        Thread.interrupted();
    }

    @Test
    void fileIsAskedForTheAddressesOfTheCommunesOfTheMetropolis() {
        file.answer(new Result.Published(PUBLISHED, List.of(ADDRESS)));

        synchronization.synchronize();

        assertThat(file.fetches())
                .containsExactly(
                        new FakeAddressFileClient.Fetch(
                                Set.of("21231", "21617"), Optional.empty()));
    }

    @Test
    void publishedFileReplacesTheStreets() {
        file.answer(new Result.Published(PUBLISHED, List.of(ADDRESS)));

        var result = synchronization.synchronize();

        assertThat(result).isEqualTo(new DatasetSynchronization(Outcome.UPDATED, 1));
        assertThat(addresses.streets()).extracting(Street::name).containsExactly("Rue Fictive");
        assertThat(addresses.findPublishedAt()).contains(PUBLISHED);
    }

    @Test
    void unchangedFileKeepsTheStreetsInPlace() {
        addresses.replace(Street.group(List.of(ADDRESS)), PUBLISHED, NOW);
        file.answer(new Result.Unchanged());

        var result = synchronization.synchronize();

        assertThat(file.fetches())
                .extracting(FakeAddressFileClient.Fetch::knownPublication)
                .containsExactly(Optional.of(PUBLISHED));
        assertThat(result.outcome()).isEqualTo(Outcome.UNCHANGED);
        assertThat(addresses.streets()).hasSize(1);
    }

    @Test
    void unavailableFileFailsTheRun() {
        file.answer(new Result.Unavailable("statut 503"));

        assertThatThrownBy(synchronization::synchronize)
                .isInstanceOf(DatasetUnavailableException.class)
                .hasMessageContaining("statut 503");
    }

    @Test
    void fileLeftUnreadBecauseTheWorkerStopsIsNoFailure() {
        file.answer(new Result.Unavailable("interrompu"));
        Thread.currentThread().interrupt();

        var result = synchronization.synchronize();

        assertThat(result.outcome()).isEqualTo(Outcome.INTERRUPTED);
    }

    @Test
    void fileWithoutAnyAddressOfTheMetropolisFailsTheRunAndKeepsTheStreets() {
        addresses.replace(Street.group(List.of(ADDRESS)), PUBLISHED, NOW);
        file.answer(new Result.Published(NOW, List.of()));

        assertThatThrownBy(synchronization::synchronize)
                .isInstanceOf(DatasetUnavailableException.class);
        assertThat(addresses.findPublishedAt()).contains(PUBLISHED);
    }
}
