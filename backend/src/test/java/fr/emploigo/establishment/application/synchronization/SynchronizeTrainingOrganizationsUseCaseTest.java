package fr.emploigo.establishment.application.synchronization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import fr.emploigo.establishment.application.synchronization.DatasetSynchronization.Outcome;
import fr.emploigo.establishment.application.synchronization.TrainingOrganizationFileClient.Result;
import fr.emploigo.establishment.domain.Siret;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;

class SynchronizeTrainingOrganizationsUseCaseTest {
    private static final Instant NOW = Instant.parse("2026-10-25T03:20:00Z");
    private static final Instant PUBLISHED = Instant.parse("2026-10-23T09:34:39Z");
    private static final Instant REPUBLISHED = Instant.parse("2026-10-30T09:00:00Z");
    private static final Siret TRAINER = new Siret("12345678900012");

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final FakeTrainingOrganizationFileClient file = new FakeTrainingOrganizationFileClient();
    private final FakeTrainingOrganizationRepository trainers =
            new FakeTrainingOrganizationRepository();
    private final SynchronizeTrainingOrganizationsUseCase synchronization =
            new SynchronizeTrainingOrganizationsUseCase(
                    file, trainers, Clock.fixed(NOW, ZoneOffset.UTC), meters);

    @AfterEach
    void clearTheInterruption() {
        Thread.interrupted();
    }

    @Test
    void publishedListReplacesTheOrganizations() {
        trainers.with(Set.of(new Siret("22345678900012")), PUBLISHED);
        file.answer(new Result.Published(REPUBLISHED, Set.of(TRAINER)));

        var result = synchronization.synchronize();

        assertThat(result).isEqualTo(new DatasetSynchronization(Outcome.UPDATED, 1));
        assertThat(trainers.findSirets()).containsExactly(TRAINER);
        assertThat(trainers.findPublishedAt()).contains(REPUBLISHED);
    }

    @Test
    void listIsAskedOnlyIfPublishedAfterTheOneInPlace() {
        trainers.with(Set.of(TRAINER), PUBLISHED);
        file.answer(new Result.Unchanged());

        var result = synchronization.synchronize();

        assertThat(file.fetches()).containsExactly(Optional.of(PUBLISHED));
        assertThat(result.outcome()).isEqualTo(Outcome.UNCHANGED);
        assertThat(trainers.findSirets()).containsExactly(TRAINER);
    }

    @Test
    void unavailableListFailsTheRunAndKeepsTheOrganizations() {
        trainers.with(Set.of(TRAINER), PUBLISHED);
        file.answer(new Result.Unavailable("statut 503"));

        assertThatThrownBy(synchronization::synchronize)
                .isInstanceOf(DatasetUnavailableException.class)
                .hasMessageContaining("statut 503");
        assertThat(trainers.findSirets()).containsExactly(TRAINER);
        assertThat(runs("failed")).isEqualTo(1);
    }

    @Test
    void listLeftUnreadBecauseTheWorkerStopsIsNoFailure() {
        file.answer(new Result.Unavailable("interrompu"));
        Thread.currentThread().interrupt();

        var result = synchronization.synchronize();

        assertThat(result.outcome()).isEqualTo(Outcome.INTERRUPTED);
        assertThat(runs("failed")).isZero();
    }

    @Test
    void listWithoutAnyOrganizationFailsTheRunAndKeepsTheOrganizations() {
        trainers.with(Set.of(TRAINER), PUBLISHED);
        file.answer(new Result.Published(REPUBLISHED, Set.of()));

        assertThatThrownBy(synchronization::synchronize)
                .isInstanceOf(DatasetUnavailableException.class);
        assertThat(trainers.findPublishedAt()).contains(PUBLISHED);
    }

    private double runs(String outcome) {
        return meters.get("dataset.synchronization.runs")
                .tags("dataset", "training-organizations", "outcome", outcome)
                .counter()
                .count();
    }
}
