package fr.emploigo.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import com.github.kagkarlsson.scheduler.Scheduler;

import fr.emploigo.support.IntegrationTest;
import fr.emploigo.support.ReservedComponents;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import java.util.stream.Stream;

/**
 * The beans of the worker are absent from the API context (the reverse is checked by {@code
 * WorkerInfrastructureIT}).
 */
class ApiWorkerSeparationIT extends IntegrationTest {
    @Autowired ApplicationContext context;

    /** Read from the compiled code, with the scheduler of the library, which is not annotated. */
    static Stream<Arguments> beansOfTheWorker() {
        return Stream.concat(
                ReservedComponents.annotatedWith(WorkerOnly.class)
                        .map(type -> argumentSet(type.getName(), type)),
                Stream.of(argumentSet("Scheduler", Scheduler.class)));
    }

    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("beansOfTheWorker")
    void apiContextHasNoBeanOfTheWorker(Class<?> type) {
        assertThat(context.getBeanNamesForType(type))
                .as("beans of type " + type.getSimpleName())
                .isEmpty();
    }
}
