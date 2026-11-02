package fr.emploigo.auth.application.purge;

import static fr.emploigo.auth.application.purge.AuthenticationPurgeRepository.BATCH_SIZE;
import static fr.emploigo.auth.application.purge.PurgeTemporaryRecordsUseCase.MAX_BATCHES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;

import fr.emploigo.support.LogCapture;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.transaction.CannotCreateTransactionException;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

class PurgeTemporaryRecordsUseCaseTest {
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-10-17T10:00:00Z"), ZoneOffset.UTC);
    private static final int BOUND = MAX_BATCHES * BATCH_SIZE;

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final FakeAuthenticationPurgeRepository repository =
            new FakeAuthenticationPurgeRepository();
    private final PurgeTemporaryRecordsUseCase purge =
            new PurgeTemporaryRecordsUseCase(repository, CLOCK, meters);

    private double purgedRows(String data) {
        return meters.get("auth.purge.rows").tag("data", data).counter().count();
    }

    @AfterEach
    void clearTheInterruption() {
        Thread.interrupted();
    }

    @Test
    void aFailureAfterSomeBatchesKeepsTheCountOfThoseBatches() {
        var answers = new AtomicInteger();
        repository.answering(
                "deleteRegistrations",
                () -> {
                    if (answers.incrementAndGet() > 2)
                        throw new IllegalStateException("fixture unavailable");
                    return BATCH_SIZE;
                });

        assertThatThrownBy(purge::purge).as("purge").isInstanceOf(IllegalStateException.class);

        assertThat(purgedRows("registrations"))
                .as("registrations counted")
                .isEqualTo(2 * BATCH_SIZE);
    }

    static Stream<Arguments> failuresThatStopTheRun() {
        return Stream.of(
                argumentSet(
                        "database that cannot be reached",
                        new CannotGetJdbcConnectionException(
                                "fixture unavailable", new SQLException("no connection")),
                        DataAccessResourceFailureException.class),
                argumentSet(
                        "transaction that cannot start",
                        new CannotCreateTransactionException("fixture unavailable"),
                        CannotCreateTransactionException.class));
    }

    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("failuresThatStopTheRun")
    void aFailureOfTheDatabaseStopsTheRunAtTheFirstAttempt(
            RuntimeException failure, Class<? extends RuntimeException> expected) {
        repository.answering(
                "deleteRegistrations",
                () -> {
                    throw failure;
                });

        assertThatThrownBy(purge::purge).as("purge").isInstanceOf(expected);

        // The failure stops the run at the second step, the deletion of the registrations.
        assertThat(repository.statements())
                .as("steps tried")
                .containsExactly("expireRegistrations", "deleteRegistrations");
    }

    @Test
    void aRunThatTheStopOfTheWorkerInterruptsBeforeItStartsHandlesNothing() {
        Thread.currentThread().interrupt();

        var purged = purge.purge();

        assertThat(purged.total()).as("rows handled").isZero();
        assertThat(repository.statements()).as("steps run").isEmpty();
    }

    @Test
    void anInterruptionThatMakesAStepFailIsNotAFailureOfTheRun() {
        repository.answering(
                "deleteRegistrations",
                () -> {
                    Thread.currentThread().interrupt();
                    throw new CannotGetJdbcConnectionException(
                            "interrupted", new SQLException("interrupted"));
                });

        assertThatCode(purge::purge).as("purge").doesNotThrowAnyException();
    }

    @Test
    void aRunStopsAtItsBoundAndReportsTheKindThatLags() {
        repository.holding("deleteGoogleFlows", BOUND + 150);

        var purged = purge.purge();

        assertThat(purged.rows()).as("rows handled").containsEntry("google-flows", BOUND);
        assertThat(purged.lagging()).as("kinds that lag").containsExactly("google-flows");
        assertThat(repository.calls("deleteGoogleFlows")).as("batches run").isEqualTo(MAX_BATCHES);
    }

    @Test
    void theRunAfterTheBoundHandlesTheRestWithoutLagging() {
        repository.holding("deleteGoogleFlows", BOUND + 150);
        purge.purge();

        var purged = purge.purge();

        assertThat(purged.rows()).as("rows handled").containsEntry("google-flows", 150);
        assertThat(purged.lagging()).as("kinds that lag").isEmpty();
    }

    @Test
    void aKindThatLagsIsLoggedOnceAsAWarning() {
        repository.holding("deleteGoogleFlows", BOUND + 150);

        try (var log = LogCapture.of(PurgeTemporaryRecordsUseCase.class)) {
            purge.purge();

            assertThat(log.events())
                    .as("warnings")
                    .filteredOn(event -> event.getLevel() == Level.WARN)
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .singleElement()
                    .asString()
                    .contains("google-flows");
        }
    }

    @Test
    void theRunCountsTheRowsItHandled() {
        repository.holding("deleteGoogleFlows", 2);

        var purged = purge.purge();

        assertThat(purged.rows()).as("rows handled").containsEntry("google-flows", 2);
        assertThat(purgedRows("google-flows")).as("rows counted").isEqualTo(2);
    }
}
