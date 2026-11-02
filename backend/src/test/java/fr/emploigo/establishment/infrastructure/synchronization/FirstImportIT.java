package fr.emploigo.establishment.infrastructure.synchronization;

import static fr.emploigo.establishment.domain.TestDirectoryEstablishment.CLASSIFIED;
import static fr.emploigo.establishment.domain.TestDirectoryEstablishment.retained;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.kagkarlsson.scheduler.task.helper.RecurringTask;
import com.github.kagkarlsson.scheduler.testhelper.ManualScheduler;
import com.github.kagkarlsson.scheduler.testhelper.SettableClock;
import com.github.kagkarlsson.scheduler.testhelper.TestHelper;

import fr.emploigo.establishment.application.synchronization.AddressFileClient;
import fr.emploigo.establishment.application.synchronization.AddressRepository;
import fr.emploigo.establishment.application.synchronization.CommuneRepository;
import fr.emploigo.establishment.application.synchronization.EstablishmentDirectoryClient;
import fr.emploigo.establishment.application.synchronization.EstablishmentRepository;
import fr.emploigo.establishment.application.synchronization.SynchronizeAddressesUseCase;
import fr.emploigo.establishment.application.synchronization.SynchronizeEstablishmentsUseCase;
import fr.emploigo.establishment.application.synchronization.SynchronizeTrainingOrganizationsUseCase;
import fr.emploigo.establishment.application.synchronization.SynchronizeTransitStopsUseCase;
import fr.emploigo.establishment.application.synchronization.TrainingOrganizationFileClient;
import fr.emploigo.establishment.application.synchronization.TrainingOrganizationRepository;
import fr.emploigo.establishment.application.synchronization.TransitFeedClient;
import fr.emploigo.establishment.application.synchronization.TransitStopRepository;
import fr.emploigo.establishment.domain.Address;
import fr.emploigo.establishment.domain.HouseNumber;
import fr.emploigo.establishment.domain.Position;
import fr.emploigo.establishment.domain.Siret;
import fr.emploigo.establishment.domain.Street;
import fr.emploigo.establishment.domain.TransitLine;
import fr.emploigo.establishment.domain.TransitMode;
import fr.emploigo.establishment.domain.TransitPlatform;
import fr.emploigo.establishment.domain.TransitStop;
import fr.emploigo.support.TestDatabase;
import fr.emploigo.support.WorkerTest;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import javax.sql.DataSource;

/**
 * The four imports as the worker declares them, on the real database and on a scheduler of its
 * own: its own table and its own clock, so the scheduler of the worker never sees them. The
 * providers are fakes that note each call.
 */
@WorkerTest
class FirstImportIT {
    private static final String TABLE = "first_import_test_tasks";
    // 02:00 in Paris, before every daily hour, on a day without a change of the hour.
    private static final Instant START = Instant.parse("2026-11-10T01:00:00Z");
    private static final Instant PUBLISHED = Instant.parse("2026-11-09T09:00:00Z");
    private static final Siret TRAINER = new Siret("98765432100015");
    private static final TransitPlatform PLATFORM =
            new TransitPlatform(
                    "Darcy",
                    new Position(5.0346, 47.3229),
                    Set.of(new TransitLine("T1", TransitMode.TRAM)));
    private static final Address ADDRESS =
            new Address(
                    "21231", "Rue Fictive", new HouseNumber(1, null, new Position(5.04, 47.32)));
    private static final TrainingOrganizationFileClient.Result PUBLISHED_TRAINERS =
            new TrainingOrganizationFileClient.Result.Published(PUBLISHED, Set.of(TRAINER));
    private static final String ESTABLISHMENTS = "synchronize-establishments";
    private static final String TRAINING_ORGANIZATIONS = "synchronize-training-organizations";
    private static final Instant TRAINERS_DAILY_HOUR = Instant.parse("2026-11-10T03:20:00Z");
    private static final Map<String, Instant> DAILY_HOURS =
            Map.of(
                    "synchronize-transit-stops", Instant.parse("2026-11-10T03:00:00Z"),
                    "synchronize-addresses", Instant.parse("2026-11-10T03:10:00Z"),
                    TRAINING_ORGANIZATIONS, TRAINERS_DAILY_HOUR,
                    ESTABLISHMENTS, Instant.parse("2026-11-10T10:00:00Z"));

    @Autowired DataSource data;
    @Autowired JdbcTemplate jdbc;
    @Autowired DatasetSynchronizationProperties datasets;
    @Autowired EstablishmentSynchronizationProperties directory;
    @Autowired TransitStopRepository stops;
    @Autowired AddressRepository addresses;
    @Autowired CommuneRepository communes;
    @Autowired TrainingOrganizationRepository trainers;
    @Autowired EstablishmentRepository establishments;

    private final Clock clock = Clock.fixed(START, ZoneOffset.UTC);
    private final List<String> calls = new CopyOnWriteArrayList<>();
    private TrainingOrganizationFileClient.Result trainingOrganizations = PUBLISHED_TRAINERS;

    @BeforeEach
    void anEmptyDatabaseAndATableOfItsOwn() {
        TestDatabase.reset(jdbc);
        jdbc.execute("CREATE TABLE %s (LIKE scheduled_tasks INCLUDING ALL)".formatted(TABLE));
    }

    @AfterEach
    void dropTheTable() {
        jdbc.execute("DROP TABLE IF EXISTS " + TABLE);
    }

    @Test
    void emptyDatabaseImportsEverythingAtStartupTheTrainingOrganizationsBeforeTheEstablishments() {
        ManualScheduler scheduler = startedScheduler();

        scheduler.runAnyDueExecutions();
        scheduler.runAnyDueExecutions();

        assertThat(calls)
                .containsExactlyInAnyOrder(
                        "transit-stops", "addresses", "training-organizations", "establishments")
                .containsSubsequence("training-organizations", "establishments");
        assertThat(executionTimes()).as("next runs").isEqualTo(DAILY_HOURS);
    }

    @Test
    void importedDatabaseWaitsForTheDailyHours() {
        importEverythingBut(null);
        ManualScheduler scheduler = startedScheduler();

        scheduler.runAnyDueExecutions();

        assertThat(calls).as("calls to the providers").isEmpty();
        assertThat(executionTimes()).as("next runs").isEqualTo(DAILY_HOURS);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {"transit-stops", "addresses", "training-organizations", "establishments"})
    void theOnlyImportThatNeverSucceededRunsAtStartup(String dataset) {
        importEverythingBut(dataset);
        ManualScheduler scheduler = startedScheduler();

        scheduler.runAnyDueExecutions();

        assertThat(calls).as("calls to the providers").containsExactly(dataset);
    }

    @Test
    void failedFirstImportOfTheTrainingOrganizationsLeavesTheEstablishmentsAtTheirDailyHour() {
        trainingOrganizations = new TrainingOrganizationFileClient.Result.Unavailable("statut 503");
        ManualScheduler scheduler = startedScheduler();

        scheduler.runAnyDueExecutions();
        scheduler.runAnyDueExecutions();

        assertThat(calls).as("calls to the providers").doesNotContain("establishments");
        assertThat(executionTimes())
                .as("next runs")
                .containsEntry("synchronize-establishments", DAILY_HOURS.get(ESTABLISHMENTS));
    }

    @Test
    void successfulRetryOfTheTrainingOrganizationsStartsTheEstablishments() {
        trainingOrganizations = new TrainingOrganizationFileClient.Result.Unavailable("statut 503");
        ManualScheduler scheduler = startedScheduler();
        scheduler.runAnyDueExecutions();
        trainingOrganizations = PUBLISHED_TRAINERS;
        calls.clear();

        scheduler.tick(DailySynchronization.RETRY_DELAY);
        scheduler.runAnyDueExecutions();
        scheduler.runAnyDueExecutions();

        assertThat(calls)
                .as("calls to the providers")
                .containsExactly("training-organizations", "establishments");
    }

    @Test
    void workersThatStartTogetherImportEachDataSetOnce() {
        ManualScheduler first = startedScheduler();
        ManualScheduler second = startedScheduler();

        first.runAnyDueExecutions();
        second.runAnyDueExecutions();
        first.runAnyDueExecutions();
        second.runAnyDueExecutions();

        assertThat(calls)
                .containsExactlyInAnyOrder(
                        "transit-stops", "addresses", "training-organizations", "establishments");
    }

    @Test
    void restartKeepsTheRetryOfAnImportThatFailed() {
        importEverythingBut(null);
        trainingOrganizations = new TrainingOrganizationFileClient.Result.Unavailable("statut 503");
        ManualScheduler scheduler = startedScheduler(START);
        scheduler.setTime(TRAINERS_DAILY_HOUR);
        scheduler.runAnyDueExecutions();
        Retry retry = retry(TRAINING_ORGANIZATIONS);

        startedScheduler(TRAINERS_DAILY_HOUR);

        assertThat(retry)
                .as("retry after the failure")
                .isEqualTo(
                        new Retry(TRAINERS_DAILY_HOUR.plus(DailySynchronization.RETRY_DELAY), 1));
        assertThat(retry(TRAINING_ORGANIZATIONS)).as("retry after the restart").isEqualTo(retry);
    }

    /** Fills every data set as an import would, except {@code missing} when given. */
    private void importEverythingBut(String missing) {
        if (!"transit-stops".equals(missing))
            stops.replace(TransitStop.group(List.of(PLATFORM)), PUBLISHED, START);
        if (!"addresses".equals(missing))
            addresses.replace(Street.group(List.of(ADDRESS)), PUBLISHED, START);
        if (!"training-organizations".equals(missing))
            trainers.replace(Set.of(TRAINER), PUBLISHED, START);
        if (!"establishments".equals(missing))
            establishments.save(List.of(retained().build().assess(CLASSIFIED, Set.of())), START);
    }

    private ManualScheduler startedScheduler() {
        return startedScheduler(START);
    }

    /** The tasks of the configurations, on the fake providers, once their scheduler started. */
    private ManualScheduler startedScheduler(Instant now) {
        var meters = new SimpleMeterRegistry();
        var datasetTasks = new DatasetSynchronizationConfiguration();
        var establishmentTask =
                new EstablishmentSynchronizationConfiguration()
                        .establishmentSynchronization(
                                directory,
                                new SynchronizeEstablishmentsUseCase(
                                        directory(),
                                        establishments,
                                        () -> CLASSIFIED,
                                        communes,
                                        trainers,
                                        clock,
                                        meters),
                                establishments,
                                trainers);
        List<RecurringTask<Void>> tasks =
                List.of(
                        datasetTasks.transitStopSynchronization(
                                datasets,
                                new SynchronizeTransitStopsUseCase(
                                        feed(), stops, clock, meters),
                                stops),
                        datasetTasks.addressSynchronization(
                                datasets,
                                new SynchronizeAddressesUseCase(
                                        addressFile(), addresses, communes, clock, meters),
                                addresses),
                        datasetTasks.trainingOrganizationSynchronization(
                                datasets,
                                new SynchronizeTrainingOrganizationsUseCase(
                                        trainingOrganizationFile(), trainers, clock, meters),
                                trainers,
                                establishmentTask),
                        establishmentTask);
        var schedulerClock = new SettableClock();
        schedulerClock.set(now);
        ManualScheduler scheduler =
                TestHelper.createManualScheduler(data)
                        .startTasks(tasks)
                        .tableName(TABLE)
                        .clock(schedulerClock)
                        .build();
        scheduler.start();
        return scheduler;
    }

    private TransitFeedClient feed() {
        return known -> {
            calls.add("transit-stops");
            return new TransitFeedClient.Result.Published(PUBLISHED, List.of(PLATFORM));
        };
    }

    private AddressFileClient addressFile() {
        return (communeCodes, known) -> {
            calls.add("addresses");
            return new AddressFileClient.Result.Published(PUBLISHED, List.of(ADDRESS));
        };
    }

    private TrainingOrganizationFileClient trainingOrganizationFile() {
        return known -> {
            calls.add("training-organizations");
            return trainingOrganizations;
        };
    }

    private EstablishmentDirectoryClient directory() {
        return (communeCodes, cursor) -> {
            calls.add("establishments");
            return new EstablishmentDirectoryClient.Result.Page(
                    1, null, List.of(retained().build()));
        };
    }

    private record Retry(Instant at, int failures) {}

    private Retry retry(String task) {
        return jdbc.queryForObject(
                """
                SELECT execution_time, consecutive_failures
                FROM %s
                WHERE task_name = ?
                """
                        .formatted(TABLE),
                (row, number) -> new Retry(row.getTimestamp(1).toInstant(), row.getInt(2)),
                task);
    }

    private Map<String, Instant> executionTimes() {
        return jdbc
                .query(
                        "SELECT task_name, execution_time FROM %s".formatted(TABLE),
                        (row, number) ->
                                Map.entry(row.getString(1), row.getTimestamp(2).toInstant()))
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }
}
