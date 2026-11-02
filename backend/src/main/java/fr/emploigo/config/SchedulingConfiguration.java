package fr.emploigo.config;

import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.boot.autoconfigure.Jackson3Serializer;
import com.github.kagkarlsson.scheduler.boot.config.DbSchedulerCustomizer;
import com.github.kagkarlsson.scheduler.serializer.Serializer;
import com.github.kagkarlsson.scheduler.task.Task;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;

import tools.jackson.databind.DeserializationFeature;

import java.util.Optional;

import javax.sql.DataSource;

/**
 * Task data is stored as JSON through this one serializer, shared by the API and the worker: a
 * record only gets new fields, which an older worker ignores and an updated worker reads as {@code
 * null} (zero for a primitive) in older data.
 */
@Configuration(proxyBeanMethods = false)
@ApiAndWorker
public class SchedulingConfiguration {
    @Bean
    Serializer schedulerSerializer() {
        return new Jackson3Serializer(
                Jackson3Serializer.getDefaultJsonMapper()
                        .rebuild()
                        .disable(
                                DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                                DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                        .build());
    }

    /**
     * The worker's scheduler (enabled by {@code db-scheduler.enabled}) uses the same serializer.
     */
    @Bean
    DbSchedulerCustomizer schedulerCustomizer(Serializer schedulerSerializer) {
        return new DbSchedulerCustomizer() {
            @Override
            public Optional<Serializer> serializer() {
                return Optional.of(schedulerSerializer);
            }
        };
    }

    /**
     * Client of the API: its connection follows the Spring transaction, so a task scheduled in a
     * rolled-back transaction does not exist; {@code @Transactional} alone does not give that.
     */
    @Bean
    @ApiOnly
    SchedulerClient schedulerClient(
            DataSource dataSource, Serializer schedulerSerializer, ObjectProvider<Task<?>> tasks) {
        return SchedulerClient.Builder.create(
                        new TransactionAwareDataSourceProxy(dataSource),
                        tasks.orderedStream().toList())
                .serializer(schedulerSerializer)
                .build();
    }
}
