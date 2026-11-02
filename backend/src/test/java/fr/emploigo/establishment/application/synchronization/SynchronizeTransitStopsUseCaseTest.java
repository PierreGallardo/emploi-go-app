package fr.emploigo.establishment.application.synchronization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import fr.emploigo.establishment.application.synchronization.DatasetSynchronization.Outcome;
import fr.emploigo.establishment.application.synchronization.TransitFeedClient.Result;
import fr.emploigo.establishment.domain.Position;
import fr.emploigo.establishment.domain.TransitLine;
import fr.emploigo.establishment.domain.TransitMode;
import fr.emploigo.establishment.domain.TransitPlatform;
import fr.emploigo.establishment.domain.TransitStop;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;

class SynchronizeTransitStopsUseCaseTest {
    private static final Instant NOW = Instant.parse("2026-10-25T03:00:00Z");
    private static final Instant PUBLISHED = Instant.parse("2026-10-23T09:34:39Z");
    private static final Instant REPUBLISHED = Instant.parse("2026-10-30T09:00:00Z");
    private static final TransitPlatform PLATFORM =
            new TransitPlatform(
                    "Darcy",
                    new Position(5.0346, 47.3229),
                    Set.of(new TransitLine("T1", TransitMode.TRAM)));

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final FakeTransitFeedClient feed = new FakeTransitFeedClient();
    private final FakeTransitStopRepository stops = new FakeTransitStopRepository();
    private final SynchronizeTransitStopsUseCase synchronization =
            new SynchronizeTransitStopsUseCase(
                    feed, stops, Clock.fixed(NOW, ZoneOffset.UTC), meters);

    @AfterEach
    void clearTheInterruption() {
        Thread.interrupted();
    }

    @Test
    void publishedFeedReplacesTheStopsWithTheInstantOfTheImport() {
        feed.answer(new Result.Published(PUBLISHED, List.of(PLATFORM)));

        var result = synchronization.synchronize();

        assertThat(result).isEqualTo(new DatasetSynchronization(Outcome.UPDATED, 1));
        assertThat(stops.stops()).extracting(TransitStop::name).containsExactly("Darcy");
        assertThat(stops.findPublishedAt()).contains(PUBLISHED);
        assertThat(stops.importedAt()).isEqualTo(NOW);
    }

    @Test
    void feedIsAskedOnlyIfPublishedAfterTheStopsInPlace() {
        stops.replace(TransitStop.group(List.of(PLATFORM)), PUBLISHED, NOW);
        feed.answer(new Result.Unchanged());

        var result = synchronization.synchronize();

        assertThat(feed.fetches()).containsExactly(Optional.of(PUBLISHED));
        assertThat(result.outcome()).isEqualTo(Outcome.UNCHANGED);
        assertThat(stops.findPublishedAt()).contains(PUBLISHED);
    }

    @Test
    void unavailableFeedFailsTheRunAndKeepsTheStops() {
        stops.replace(TransitStop.group(List.of(PLATFORM)), PUBLISHED, NOW);
        feed.answer(new Result.Unavailable("statut 503"));

        assertThatThrownBy(synchronization::synchronize)
                .isInstanceOf(DatasetUnavailableException.class)
                .hasMessageContaining("statut 503");
        assertThat(stops.stops()).hasSize(1);
        assertThat(
                        meters.get("dataset.synchronization.runs")
                                .tags("dataset", "transit-stops", "outcome", "failed")
                                .counter()
                                .count())
                .isEqualTo(1);
    }

    @Test
    void feedLeftUnreadBecauseTheWorkerStopsIsNoFailure() {
        feed.answer(new Result.Unavailable("interrompu"));
        Thread.currentThread().interrupt();

        var result = synchronization.synchronize();

        assertThat(result.outcome()).isEqualTo(Outcome.INTERRUPTED);
        assertThat(
                        meters.get("dataset.synchronization.runs")
                                .tags("dataset", "transit-stops", "outcome", "failed")
                                .counter()
                                .count())
                .isZero();
    }

    @Test
    void feedWithoutAnyServedPlatformFailsTheRunAndKeepsTheStops() {
        stops.replace(TransitStop.group(List.of(PLATFORM)), PUBLISHED, NOW);
        feed.answer(new Result.Published(REPUBLISHED, List.of()));

        assertThatThrownBy(synchronization::synchronize)
                .isInstanceOf(DatasetUnavailableException.class);
        assertThat(stops.findPublishedAt()).contains(PUBLISHED);
    }
}
