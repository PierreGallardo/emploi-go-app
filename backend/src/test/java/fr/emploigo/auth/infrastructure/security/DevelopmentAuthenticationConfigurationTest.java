package fr.emploigo.auth.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;
import static org.mockito.Mockito.mock;

import fr.emploigo.auth.application.development.CreateDevelopmentAccountUseCase;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.stream.Stream;

class DevelopmentAuthenticationConfigurationTest {
    private final ApplicationContextRunner context =
            new ApplicationContextRunner()
                    .withUserConfiguration(DevelopmentAuthenticationConfiguration.class)
                    .withBean(
                            CreateDevelopmentAccountUseCase.class,
                            () -> mock(CreateDevelopmentAccountUseCase.class));

    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("profilesOutsideLocalApi")
    void fixtureIsNotCreatedOutsideLocalApiEvenWithItsFlag(String profile) {
        context.withPropertyValues(
                        "spring.profiles.active=" + profile,
                        "emploi-go.auth.development-account=true")
                .run(
                        application ->
                                assertThat(application).doesNotHaveBean(ApplicationRunner.class));
    }

    static Stream<Arguments> profilesOutsideLocalApi() {
        return Stream.of(
                argumentSet("production", "production"),
                argumentSet("test", "test"),
                argumentSet("contract", "contract"),
                argumentSet("local avec worker", "local,worker"));
    }

    @Test
    void localFixtureIsDisabledByDefault() {
        context.withPropertyValues("spring.profiles.active=local")
                .run(
                        application ->
                                assertThat(application).doesNotHaveBean(ApplicationRunner.class));
    }

    @Test
    void localFixtureWithItsFlagAndAnExplicitPasswordCreatesTheRunner() {
        context.withPropertyValues(
                        "spring.profiles.active=local",
                        "emploi-go.auth.development-account=true",
                        "DEV_AUTH_PASSWORD=development-fixture-secret")
                .run(application -> assertThat(application).hasSingleBean(ApplicationRunner.class));
    }

    @Test
    void localFixtureWithoutAnExplicitPasswordFailsTheStartup() {
        context.withPropertyValues(
                        "spring.profiles.active=local",
                        "emploi-go.auth.development-account=true",
                        "DEV_AUTH_PASSWORD=")
                .run(application -> assertThat(application).hasFailed());
    }
}
