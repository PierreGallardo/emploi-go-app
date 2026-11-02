package fr.emploigo.establishment.application.synchronization;

import static fr.emploigo.establishment.domain.TestDirectoryEstablishment.CLASSIFIED;
import static fr.emploigo.establishment.domain.TestDirectoryEstablishment.retained;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import fr.emploigo.establishment.application.synchronization.EstablishmentDirectoryClient.Result;
import fr.emploigo.establishment.application.synchronization.FakeEstablishmentRepository.Row;
import fr.emploigo.establishment.application.synchronization.SynchronizeEstablishmentsUseCase.Outcome;
import fr.emploigo.establishment.domain.Assessment;
import fr.emploigo.establishment.domain.CompanyType;
import fr.emploigo.establishment.domain.DirectoryEstablishment;
import fr.emploigo.establishment.domain.Establishment;
import fr.emploigo.establishment.domain.ExclusionReason;
import fr.emploigo.establishment.domain.Siret;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

class SynchronizeEstablishmentsUseCaseTest {
    private static final Instant NOW = Instant.parse("2026-10-25T01:30:00.123456789Z");
    private static final Instant RUN = Instant.parse("2026-10-25T01:30:00.123456Z");
    private static final Instant YESTERDAY = Instant.parse("2026-10-24T01:30:00Z");

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final FakeEstablishmentDirectoryClient directory =
            new FakeEstablishmentDirectoryClient();
    private final FakeEstablishmentRepository establishments = new FakeEstablishmentRepository();
    private final FakeTrainingOrganizationRepository trainers =
            new FakeTrainingOrganizationRepository();
    private List<String> communeCodes = List.of("21231", "21617");

    private SynchronizeEstablishmentsUseCase synchronization() {
        return new SynchronizeEstablishmentsUseCase(
                directory,
                establishments,
                () -> CLASSIFIED,
                () -> communeCodes,
                trainers,
                Clock.fixed(NOW, ZoneOffset.UTC),
                meters);
    }

    @AfterEach
    void clearTheInterruption() {
        Thread.interrupted();
    }

    @Test
    void everyPageIsSavedWithTheInstantOfTheRun() {
        directory
                .answer(page(2, "cursor-2", "12345678900012"))
                .answer(page(2, null, "22345678900012"));

        synchronization().synchronize();

        assertThat(establishments.rows())
                .containsOnlyKeys(new Siret("12345678900012"), new Siret("22345678900012"));
        assertThat(establishments.rows().values()).extracting(Row::seenAt).containsOnly(RUN);
    }

    @Test
    void pagesAreAskedForOneAfterTheOtherForAllTheCommunes() {
        directory
                .answer(page(2, "cursor-2", "12345678900012"))
                .answer(page(2, null, "22345678900012"));

        synchronization().synchronize();

        assertThat(directory.cursors()).containsExactly(null, "cursor-2");
        assertThat(directory.communes()).containsOnly(List.of("21231", "21617"));
    }

    @Test
    void establishmentsAreCountedByResult() {
        directory.answer(
                new Result.Page(
                        3,
                        null,
                        List.of(
                                retained().siret("12345678900012").build(),
                                retained().siret("12345678900020").activities(null, null).build(),
                                retained().siret("22345678900012").diffusible(false).build())));

        var result = synchronization().synchronize();

        assertThat(result.establishments())
                .isEqualTo(
                        Map.of(
                                "retained", 1,
                                "excluded-activity", 1,
                                "withheld", 1,
                                "not-found", 0));
        assertThat(establishmentsCounted("excluded-activity")).isEqualTo(1);
    }

    @Test
    void establishmentOfTheListOfTrainingOrganizationsIsRetainedAsTraining() {
        trainers.with(Set.of(new Siret("12345678900012")), YESTERDAY);
        directory.answer(page(1, null, "12345678900012"));

        synchronization().synchronize();

        assertThat(establishments.rows().get(new Siret("12345678900012")).establishment())
                .extracting(Establishment::companyType)
                .isEqualTo(CompanyType.TRAINING);
    }

    @Test
    void completeRunMarksTheEstablishmentsItDidNotSeeAsNotFound() {
        establishments.with(establishment("42345678900012"), YESTERDAY);
        directory.answer(page(1, null, "12345678900012"));

        var result = synchronization().synchronize();

        assertThat(result.outcome()).isEqualTo(Outcome.COMPLETE);
        assertThat(establishments.rows().get(new Siret("42345678900012")).exclusion())
                .isEqualTo(ExclusionReason.NOT_FOUND);
    }

    @Test
    void runThatReadsFewerEstablishmentsThanAnnouncedIsIncomplete() {
        establishments.with(establishment("42345678900012"), YESTERDAY);
        directory.answer(page(2, null, "12345678900012"));

        var result = synchronization().synchronize();

        assertThat(result.outcome()).isEqualTo(Outcome.INCOMPLETE);
        assertThat(establishments.rows()).containsKey(new Siret("12345678900012"));
        assertThat(establishments.rows().get(new Siret("42345678900012")).exclusion()).isNull();
    }

    @Test
    void runThatReadsMoreEstablishmentsThanAnnouncedIsIncomplete() {
        establishments.with(establishment("42345678900012"), YESTERDAY);
        directory.answer(page(1, null, "12345678900012", "22345678900012"));

        var result = synchronization().synchronize();

        assertThat(result.outcome()).isEqualTo(Outcome.INCOMPLETE);
        assertThat(establishments.rows().get(new Siret("42345678900012")).exclusion()).isNull();
    }

    @Test
    void totalAnnouncedByTheLastPageIsTheOneThatCounts() {
        establishments.with(establishment("42345678900012"), YESTERDAY);
        directory
                .answer(page(5, "cursor-2", "12345678900012"))
                .answer(page(2, null, "22345678900012"));

        var result = synchronization().synchronize();

        assertThat(result.outcome()).isEqualTo(Outcome.COMPLETE);
    }

    @Test
    void runThatReadsNoEstablishmentAtAllIsIncomplete() {
        establishments.with(establishment("42345678900012"), YESTERDAY);
        directory.answer(new Result.Page(0, null, List.of()));

        var result = synchronization().synchronize();

        assertThat(result.outcome()).isEqualTo(Outcome.INCOMPLETE);
        assertThat(establishments.rows().get(new Siret("42345678900012")).exclusion()).isNull();
    }

    @Test
    void runWithoutCommuneAsksNothingAndIsIncomplete() {
        communeCodes = List.of();

        var result = synchronization().synchronize();

        assertThat(result.outcome()).isEqualTo(Outcome.INCOMPLETE);
        assertThat(directory.cursors()).isEmpty();
    }

    @Test
    void unavailableDirectoryFailsTheRunAndKeepsWhatWasSaved() {
        establishments.with(establishment("42345678900012"), YESTERDAY);
        directory
                .answer(page(2, "cursor-2", "12345678900012"))
                .answer(new Result.Unavailable("503"));

        assertThatThrownBy(synchronization()::synchronize)
                .isInstanceOf(DirectoryUnavailableException.class)
                .hasMessageContaining("503")
                .hasMessageContaining("page 2");
        assertThat(establishments.rows().get(new Siret("12345678900012")).seenAt()).isEqualTo(RUN);
        assertThat(establishments.rows().get(new Siret("42345678900012")).exclusion()).isNull();
        assertThat(runs("failed")).isEqualTo(1);
    }

    @Test
    void stoppingWorkerBetweenTwoPagesInterruptsTheRun() {
        establishments.with(establishment("42345678900012"), YESTERDAY);
        directory
                .answer(page(2, "cursor-2", "12345678900012"))
                .answer(page(2, null, "22345678900012"))
                .interruptAt(2);

        var result = synchronization().synchronize();

        assertThat(result.outcome()).isEqualTo(Outcome.INTERRUPTED);
        assertThat(establishments.rows())
                .containsOnlyKeys(new Siret("12345678900012"), new Siret("42345678900012"));
        assertThat(establishments.rows().get(new Siret("42345678900012")).exclusion()).isNull();
        assertThat(runs("interrupted")).isEqualTo(1);
    }

    private double establishmentsCounted(String result) {
        return meters.get("establishment.synchronization.establishments")
                .tag("result", result)
                .counter()
                .count();
    }

    private double runs(String outcome) {
        return meters.get("dataset.synchronization.runs")
                .tag("dataset", "establishments")
                .tag("outcome", outcome)
                .counter()
                .count();
    }

    /** A page of retained establishments with these SIRETs. */
    private static Result.Page page(int total, String nextCursor, String... sirets) {
        return new Result.Page(
                total,
                nextCursor,
                Arrays.stream(sirets).map(siret -> retained().siret(siret).build()).toList());
    }

    private static Establishment establishment(String siret) {
        DirectoryEstablishment directoryEstablishment = retained().siret(siret).build();
        var assessment = directoryEstablishment.assess(CLASSIFIED, Set.of());
        return ((Assessment.Retained) assessment).establishment();
    }
}
