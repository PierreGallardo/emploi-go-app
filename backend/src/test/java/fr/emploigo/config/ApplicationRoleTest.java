package fr.emploigo.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.mock.env.MockEnvironment;

import java.util.stream.Stream;

class ApplicationRoleTest {
    private final ApplicationContextRunner context =
            new ApplicationContextRunner().withUserConfiguration(
                            ApiBeans.class,
                            WorkerBeans.class,
                            SharedBeans.class,
                            LocalApiBeans.class);

    @ParameterizedTest(name = "{0}")
    @MethodSource("rolesByProfiles")
    void roleIsTheWorkerOnlyWhenItsProfileIsActive(String profiles, ApplicationRole role) {
        var environment = new MockEnvironment();
        environment.setActiveProfiles(profiles.isEmpty() ? new String[0] : profiles.split(","));

        assertThat(ApplicationRole.of(environment)).isEqualTo(role);
    }

    static Stream<Arguments> rolesByProfiles() {
        return Stream.of(
                argumentSet("no profile", "", ApplicationRole.API),
                argumentSet("worker", "worker", ApplicationRole.WORKER),
                argumentSet("local and worker", "local,worker", ApplicationRole.WORKER),
                argumentSet("production", "production", ApplicationRole.API));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("beansByProfiles")
    void componentsAreLoadedByTheirRoleAndCombineWithAProfileAsAnAnd(
            String profiles, String expected) {
        context.withPropertyValues("spring.profiles.active=" + profiles)
                .run(
                        loaded ->
                                assertThat(loaded.getBeansOfType(String.class).keySet())
                                        .containsExactlyInAnyOrder(expected.split(",")));
    }

    static Stream<Arguments> beansByProfiles() {
        return Stream.of(
                argumentSet("API", "test", "api,shared"),
                argumentSet("local API", "local", "api,shared,localApi"),
                argumentSet("worker", "worker", "worker,shared"),
                argumentSet("local worker", "local,worker", "worker,shared"));
    }

    @Configuration(proxyBeanMethods = false)
    @ApiOnly
    static class ApiBeans {
        @Bean
        String api() {
            return "api";
        }
    }

    @Configuration(proxyBeanMethods = false)
    @WorkerOnly
    static class WorkerBeans {
        @Bean
        String worker() {
            return "worker";
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ApiAndWorker
    static class SharedBeans {
        @Bean
        String shared() {
            return "shared";
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ApiOnly
    @Profile("local")
    static class LocalApiBeans {
        @Bean
        String localApi() {
            return "localApi";
        }
    }
}
