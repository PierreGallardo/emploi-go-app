package fr.emploigo.auth.infrastructure.purge;

import fr.emploigo.auth.application.purge.AuthenticationPurgeRepository;
import fr.emploigo.auth.application.purge.PurgeTemporaryRecordsUseCase;
import fr.emploigo.support.TestClock;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.springframework.beans.factory.config.AutowireCapableBeanFactory;

import java.time.Instant;

/**
 * Runs the authentication purge as the worker does, for API tests: the worker is not in their
 * context and the API purges nothing itself.
 */
public final class WorkerPurge {
    private WorkerPurge() {}

    /** Runs the purge at {@code instant}, which becomes the time of the test. */
    public static PurgeTemporaryRecordsUseCase.Purged runAt(
            AutowireCapableBeanFactory beans, TestClock clock, Instant instant) {
        clock.now = instant;
        var meters = new SimpleMeterRegistry();
        return new PurgeTemporaryRecordsUseCase(repository(beans), clock, meters).purge();
    }

    /**
     * The repository as Spring creates it for the worker, with the translation of its errors: the
     * bean is not in the context of the API, so it is created here.
     */
    public static AuthenticationPurgeRepository repository(AutowireCapableBeanFactory beans) {
        return beans.createBean(JpaAuthenticationPurgeRepository.class);
    }
}
