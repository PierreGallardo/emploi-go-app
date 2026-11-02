package fr.emploigo.auth.infrastructure.purge;

import com.github.kagkarlsson.scheduler.task.helper.RecurringTask;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.Schedules;

import fr.emploigo.auth.application.purge.PurgeTemporaryRecordsUseCase;
import fr.emploigo.config.WorkerOnly;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Runs one at a time across the workers, and is safe to repeat: it only handles the rows that are
 * due.
 */
@Configuration(proxyBeanMethods = false)
@WorkerOnly
@EnableConfigurationProperties(PurgeProperties.class)
class AuthenticationPurgeConfiguration {
    static final String NAME = "purge-authentication-data";

    @Bean
    RecurringTask<Void> authenticationPurge(PurgeProperties properties, PurgeTemporaryRecordsUseCase purge) {
        return Tasks.recurring(NAME, Schedules.fixedDelay(properties.interval()))
                .onFailureReschedule()
                .onDeadExecutionRevive()
                .execute((instance, context) -> purge.purge());
    }
}
