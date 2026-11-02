package fr.emploigo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import com.github.kagkarlsson.scheduler.Scheduler;
import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.task.TaskInstanceId;
import com.github.kagkarlsson.scheduler.task.helper.RecurringTask;

import fr.emploigo.auth.application.purge.PurgeTemporaryRecordsUseCase;
import fr.emploigo.auth.infrastructure.purge.JpaAuthenticationPurgeRepository;
import fr.emploigo.config.ApiOnly;
import fr.emploigo.config.SecurityConfiguration;
import fr.emploigo.establishment.application.synchronization.SynchronizeEstablishmentsUseCase;
import fr.emploigo.support.ReservedComponents;
import fr.emploigo.support.SchedulerFixtures;
import fr.emploigo.support.SqlFailure;
import fr.emploigo.support.TestDatabase;
import fr.emploigo.support.WorkerTest;

import io.swagger.v3.oas.models.OpenAPI;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.core.type.MethodMetadata;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.session.SessionRepository;
import org.springframework.session.web.http.SessionRepositoryFilter;
import org.springframework.stereotype.Controller;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import javax.sql.DataSource;

/** The purge is due again five minutes after each run, so it does not purge while the tests run. */
@WorkerTest
class WorkerInfrastructureIT {
    private static final String APPLICATION_PORT = "local.server.port";
    private static final String MANAGEMENT_PORT = "local.management.port";

    /**
     * The name of the task in {@code scheduled_tasks}: it is persisted, so a rename must be a
     * migration.
     */
    private static final TaskInstanceId PURGE =
            TaskInstanceId.of("purge-authentication-data", RecurringTask.INSTANCE);

    private static final Duration SCHEDULER_ANSWER = Duration.ofSeconds(30);

    @Autowired ApplicationContext context;
    @Autowired ConfigurableListableBeanFactory beanFactory;
    @Autowired Environment environment;
    @Autowired Scheduler scheduler;
    @Autowired JdbcTemplate jdbc;

    /**
     * Waits for the purge in progress before emptying the tables, so that no run comes while a test
     * inserts its rows.
     */
    @BeforeEach
    void emptyTheTables() {
        runThePurgeNow();
        awaitTheSuccessOfTheRun();
        TestDatabase.reset(jdbc);
        jdbc.update("DELETE FROM scheduled_tasks WHERE task_name = ?", SchedulerFixtures.TASK);
        SchedulerFixtures.RECEIVED.clear();
    }

    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("beansOfTheWorker")
    void workerContextHasExactlyOneBeanOfTheType(Class<?> type) {
        assertThat(context.getBeansOfType(type))
                .as("beans of type " + type.getSimpleName())
                .hasSize(1);
    }

    static Stream<Arguments> beansOfTheWorker() {
        return Stream.of(
                argumentSet("DataSource", DataSource.class),
                argumentSet("Scheduler", Scheduler.class),
                argumentSet("Clock", Clock.class),
                argumentSet("PurgeTemporaryRecordsUseCase", PurgeTemporaryRecordsUseCase.class),
                argumentSet(
                        "JpaAuthenticationPurgeRepository", JpaAuthenticationPurgeRepository.class),
                argumentSet(
                        "SynchronizeEstablishmentsUseCase",
                        SynchronizeEstablishmentsUseCase.class));
    }

    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("beansOfTheApi")
    void workerContextHasNoBeanOfTheApi(Class<?> type) {
        assertThat(context.getBeansOfType(type))
                .as("beans of type " + type.getSimpleName())
                .isEmpty();
    }

    static Stream<Arguments> beansOfTheApi() {
        return Stream.of(
                argumentSet("OpenAPI", OpenAPI.class),
                argumentSet("SessionRepository", SessionRepository.class),
                argumentSet("SessionRepositoryFilter", SessionRepositoryFilter.class),
                argumentSet("RedisConnectionFactory", RedisConnectionFactory.class),
                argumentSet("ReactiveRedisConnectionFactory", ReactiveRedisConnectionFactory.class),
                argumentSet("RedisTemplate", RedisTemplate.class));
    }

    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("classesReservedToTheApi")
    void workerContextHasNoComponentReservedToTheApi(Class<?> type) {
        assertThat(context.getBeanNamesForType(type))
                .as("beans of type " + type.getName())
                .isEmpty();
    }

    /**
     * Read from the compiled code: the classes annotated {@code @ApiOnly}, and the components of an
     * {@code api} package, which an omitted annotation would not exclude.
     */
    static Stream<Arguments> classesReservedToTheApi() {
        return Stream.concat(
                        ReservedComponents.annotatedWith(ApiOnly.class),
                        ReservedComponents.componentsInApiPackages())
                .distinct()
                .map(type -> argumentSet(type.getName(), type));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("beanMethodsReservedToTheApi")
    void workerContextHasNoBeanFromAMethodReservedToTheApi(String method) {
        var created =
                Stream.of(beanFactory.getBeanDefinitionNames())
                        .map(beanFactory::getBeanDefinition)
                        .filter(AnnotatedBeanDefinition.class::isInstance)
                        .map(AnnotatedBeanDefinition.class::cast)
                        .map(AnnotatedBeanDefinition::getFactoryMethodMetadata)
                        .filter(Objects::nonNull)
                        .map(
                                metadata ->
                                        metadata.getDeclaringClassName()
                                                + "#"
                                                + metadata.getMethodName())
                        .toList();

        assertThat(created).as("bean methods run by the worker").doesNotContain(method);
    }

    static Stream<String> beanMethodsReservedToTheApi() {
        return ReservedComponents.methodsAnnotatedWith(ApiOnly.class);
    }

    /** The worker serves only its supervision: no controller of the application. */
    @Test
    void workerHasNoControllerOfTheApplication() {
        assertThat(context.getBeansWithAnnotation(Controller.class).values())
                .as("controllers of the application")
                .noneMatch(bean -> bean.getClass().getName().startsWith("fr.emploigo"));
    }

    /** The only filter chain is the one of the supervision, never the one of the API. */
    @Test
    void workerHasOnlyTheFilterChainOfItsSupervision() {
        var declaringClasses =
                Stream.of(context.getBeanNamesForType(SecurityFilterChain.class))
                        .map(beanFactory::getBeanDefinition)
                        .map(AnnotatedBeanDefinition.class::cast)
                        .map(AnnotatedBeanDefinition::getFactoryMethodMetadata)
                        .map(MethodMetadata::getDeclaringClassName)
                        .toList();

        assertThat(declaringClasses).containsExactly(SecurityConfiguration.class.getName());
    }

    @Test
    void schedulerReadsItsTaskTable() {
        assertThatCode(scheduler::getScheduledExecutions)
                .as("reading the scheduled_tasks table")
                .doesNotThrowAnyException();
    }

    @Test
    void theSchedulerOfTheWorkerIsItsClient() {
        assertThat(context.getBeansOfType(SchedulerClient.class).values())
                .as("clients")
                .containsExactly(scheduler);
    }

    @Test
    void theWorkerReadsTheDataThatTheApiStoredIdentically() throws InterruptedException {
        scheduleAs("from-the-api", SchedulerFixtures.PAYLOAD_JSON);

        assertThat(SchedulerFixtures.RECEIVED.poll(SCHEDULER_ANSWER.toSeconds(), TimeUnit.SECONDS))
                .as("data received by the task")
                .isEqualTo(SchedulerFixtures.PAYLOAD);
    }

    @Test
    void theWorkerIgnoresAFieldThatAMoreRecentApiAdded() throws InterruptedException {
        String json = SchedulerFixtures.PAYLOAD_JSON;
        String withAnAddedField = json.substring(0, json.length() - 1) + ",\"priority\":\"high\"}";

        scheduleAs("from-a-newer-api", withAnAddedField);

        assertThat(SchedulerFixtures.RECEIVED.poll(SCHEDULER_ANSWER.toSeconds(), TimeUnit.SECONDS))
                .as("data received by the task")
                .isEqualTo(SchedulerFixtures.PAYLOAD);
    }

    @Test
    void anUpdatedWorkerReadsAFieldThatTheApiDidNotWriteYetAsMissing() throws InterruptedException {
        scheduleAs("from-an-older-api", "{\"label\":\"Rappel\",\"at\":\"2026-10-20T08:30:00Z\"}");

        assertThat(SchedulerFixtures.RECEIVED.poll(SCHEDULER_ANSWER.toSeconds(), TimeUnit.SECONDS))
                .as("data received by the task")
                .isEqualTo(
                        new SchedulerFixtures.Payload(
                                "Rappel", 0, Instant.parse("2026-10-20T08:30:00Z"), null, null));
    }

    /**
     * Stores a task as the API does, due now, and asks the scheduler to look for it instead of
     * waiting for its hourly look.
     */
    private void scheduleAs(String instance, String json) {
        jdbc.update(
                """
                INSERT INTO scheduled_tasks(
                    task_name, task_instance, task_data, execution_time, picked, version)
                VALUES (?, ?, ?, ?, FALSE, 1)
                """,
                SchedulerFixtures.TASK,
                instance,
                json.getBytes(StandardCharsets.UTF_8),
                Timestamp.from(Instant.now()));
        scheduler.triggerCheckForDueExecutions();
    }

    @Test
    void thePurgeIsScheduledOnceAtTheConfiguredInterval() {
        assertThat(scheduler.getScheduledExecutionsForTask(PURGE.getTaskName()))
                .as("scheduled instances of the purge")
                .hasSize(1);
        assertThat(environment.getProperty("emploi-go.purge.interval", Duration.class))
                .as("interval")
                .isEqualTo(Duration.ofMinutes(5));
    }

    /**
     * The hour of an import depends on the data left by the tests that ran before: one never
     * imported runs at startup. {@code FirstImportIT} checks their hours.
     */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "synchronize-establishments",
                "synchronize-transit-stops",
                "synchronize-addresses",
                "synchronize-training-organizations",
                "purge-excluded-establishments"
            })
    void eachDailyTaskIsScheduledOnce(String task) {
        assertThat(scheduler.getScheduledExecutionsForTask(task))
                .as("scheduled instances of the task")
                .hasSize(1);
    }

    @Test
    void thePurgeOfTheExcludedEstablishmentsIsScheduledAtItsHourOfParis() {
        assertThat(scheduler.getScheduledExecutionsForTask("purge-excluded-establishments"))
                .as("scheduled instances of the task")
                .singleElement()
                .extracting(
                        execution ->
                                execution
                                        .getExecutionTime()
                                        .atZone(ZoneId.of("Europe/Paris"))
                                        .toLocalTime())
                .isEqualTo(LocalTime.of(5, 30));
    }

    @Test
    void theTemporaryDataThatIsDueIsPurgedWithoutAnyRequest() {
        insertAnExpiredGoogleFlow();

        runThePurgeNow();

        await().atMost(SCHEDULER_ANSWER)
                .untilAsserted(
                        () -> {
                            assertThat(
                                            jdbc.queryForObject(
                                                    "SELECT COUNT(*) FROM auth_google_flows",
                                                    Long.class))
                                    .as("starts left")
                                    .isZero();
                        });
    }

    @Test
    void aPurgeThatFailsIsScheduledAgainAndSucceedsOnceTheCauseIsGone() {
        insertAnExpiredGoogleFlow();

        try (var failure = SqlFailure.inject(jdbc, "auth_google_flows", "BEFORE DELETE")) {
            runThePurgeNow();
            // Rescheduling clears the failures: one is counted only once a run has failed.
            await().atMost(SCHEDULER_ANSWER)
                    .untilAsserted(
                            () ->
                                    assertThat(scheduler.getScheduledExecution(PURGE))
                                            .as("the purge after its failure")
                                            .hasValueSatisfying(
                                                    execution ->
                                                            assertThat(
                                                                            execution
                                                                                    .getConsecutiveFailures())
                                                                    .as("consecutive failures")
                                                                    .isEqualTo(1)));
        }
        runThePurgeNow();

        awaitTheSuccessOfTheRun();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM auth_google_flows", Long.class))
                .as("starts left")
                .isZero();
    }

    private void insertAnExpiredGoogleFlow() {
        jdbc.update(
                """
                INSERT INTO auth_google_flows(state_hash, browser_hash, expires_at)
                VALUES (?, ?, ?)
                """,
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                Timestamp.from(Instant.now().minus(Duration.ofHours(25))));
    }

    /**
     * Reschedules the purge to now and asks for a look; rescheduling clears the last success and
     * the failures, which come back only once a run has ended.
     */
    private void runThePurgeNow() {
        await().atMost(SCHEDULER_ANSWER)
                .until(
                        () ->
                                scheduler
                                        .getScheduledExecution(PURGE)
                                        .filter(execution -> !execution.isPicked())
                                        .isPresent());
        scheduler.reschedule(PURGE, Instant.now());
        scheduler.triggerCheckForDueExecutions();
    }

    private void awaitTheSuccessOfTheRun() {
        await().atMost(SCHEDULER_ANSWER)
                .untilAsserted(
                        () ->
                                assertThat(scheduler.getScheduledExecution(PURGE))
                                        .as("the purge after its run")
                                        .hasValueSatisfying(
                                                execution ->
                                                        assertThat(execution.getLastSuccess())
                                                                .as("last success")
                                                                .isNotNull()));
    }

    @Test
    void healthAnswers200UpWithoutSessionCookie() throws Exception {
        var health = get(MANAGEMENT_PORT, "/actuator/health");

        assertThat(health.statusCode()).as("status").isEqualTo(200);
        assertThat(health.body()).as("body").contains("\"status\":\"UP\"");
        assertThat(health.headers().allValues("set-cookie")).as("cookies set").isEmpty();
    }

    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("protectedEndpoints")
    void protectedEndpointAnswers401WithoutSessionCookie(String portProperty, String path)
            throws Exception {
        var response = get(portProperty, path);

        assertThat(response.statusCode()).as("status").isEqualTo(401);
        assertThat(response.headers().allValues("set-cookie")).as("cookies set").isEmpty();
    }

    static Stream<Arguments> protectedEndpoints() {
        return Stream.of(
                argumentSet("ping de l'API", APPLICATION_PORT, "/api/ping"),
                argumentSet("documentation OpenAPI", APPLICATION_PORT, "/v3/api-docs"),
                argumentSet("métriques", MANAGEMENT_PORT, "/actuator/prometheus"));
    }

    private HttpResponse<String> get(String portProperty, String path) throws Exception {
        int port = environment.getRequiredProperty(portProperty, Integer.class);
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
        }
    }
}
