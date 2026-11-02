package fr.emploigo.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;

import java.util.stream.Stream;

class ApplicationProfilesTest {
    private ApplicationContextRunner configuration(String profiles) {
        return new ApplicationContextRunner()
                .withPropertyValues("spring.profiles.active=" + profiles)
                .withInitializer(new ConfigDataApplicationContextInitializer());
    }

    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("apiProfiles")
    void apiConfigurationIsLoaded(String profiles) {
        configuration(profiles)
                .run(
                        context -> {
                            var environment = context.getEnvironment();
                            assertThat(environment.getProperty("spring.application.name"))
                                    .as("application name")
                                    .isEqualTo("emploi-go-api");
                            assertThat(
                                            environment.getProperty(
                                                    "spring.session.jdbc.initialize-schema"))
                                    .as("session schema")
                                    .isEqualTo("never");
                            assertThat(environment.getProperty("db-scheduler.enabled"))
                                    .as("scheduler enabled")
                                    .isEqualTo("false");
                        });
    }

    static Stream<Arguments> apiProfiles() {
        return Stream.of(
                argumentSet("par défaut", ""),
                argumentSet("local", "local"),
                argumentSet("contract", "contract"),
                argumentSet("test", "test"));
    }

    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("workerProfiles")
    void workerDoesNotImportApiSessionOrRedisSettings(String profiles) {
        configuration(profiles)
                .run(
                        context -> {
                            var environment = context.getEnvironment();
                            assertThat(environment.getProperty("spring.application.name"))
                                    .as("application name")
                                    .isEqualTo("emploi-go-worker");
                            assertThat(
                                            environment.getProperty(
                                                    "spring.session.jdbc.initialize-schema"))
                                    .as("session schema")
                                    .isNull();
                            assertThat(environment.containsProperty("spring.data.redis.host"))
                                    .as("redis host defined")
                                    .isFalse();
                            assertThat(environment.getProperty("db-scheduler.enabled"))
                                    .as("scheduler enabled")
                                    .isEqualTo("true");
                            assertThat(environment.getProperty("springdoc.api-docs.enabled"))
                                    .as("api docs enabled")
                                    .isEqualTo("false");
                        });
    }

    static Stream<Arguments> workerProfiles() {
        return Stream.of(
                argumentSet("worker", "worker"),
                argumentSet("local puis worker", "local,worker"),
                argumentSet("worker puis local", "worker,local"));
    }

    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("apiProfiles")
    void sessionTimeoutDefaultsToThirtyMinutes(String profiles) {
        // Without the system environment, so that a SESSION_TIMEOUT exported in the shell cannot
        // change the default.
        configuration(profiles)
                .withInitializer(
                        context ->
                                context.getEnvironment()
                                        .getPropertySources()
                                        .remove(
                                                StandardEnvironment
                                                        .SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME))
                .run(
                        context ->
                                assertThat(
                                                context.getEnvironment()
                                                        .getProperty("spring.session.timeout"))
                                        .as("session timeout")
                                        .isEqualTo("30m"));
    }

    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("apiProfiles")
    void sessionTimeoutFollowsTheSessionTimeoutVariable(String profiles) {
        configuration(profiles)
                .withPropertyValues("SESSION_TIMEOUT=5m")
                .run(
                        context ->
                                assertThat(
                                                context.getEnvironment()
                                                        .getProperty("spring.session.timeout"))
                                        .as("session timeout")
                                        .isEqualTo("5m"));
    }

    static Stream<Arguments> sessionCookieNames() {
        return Stream.of(
                argumentSet("par défaut", "", "__Host-EMPLOIGO_SESSION"),
                argumentSet("test", "test", "__Host-EMPLOIGO_SESSION"),
                argumentSet("contract", "contract", "__Host-EMPLOIGO_SESSION"),
                argumentSet("local", "local", "EMPLOIGO_SESSION"),
                argumentSet("local, contract et test", "local,contract,test", "EMPLOIGO_SESSION"));
    }

    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("sessionCookieNames")
    void sessionCookieHasTheHostPrefixExceptWithTheLocalProfile(String profiles, String name) {
        configuration(profiles)
                .run(
                        context ->
                                assertThat(
                                                context.getEnvironment()
                                                        .getProperty(
                                                                "server.servlet.session.cookie.name"))
                                        .as("session cookie name")
                                        .isEqualTo(name));
    }
}
