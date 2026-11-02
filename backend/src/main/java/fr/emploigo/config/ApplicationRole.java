package fr.emploigo.config;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * The role of the process, the only place of the code that names the {@code worker} profile: the
 * API unless that profile is active. Components declare theirs with {@link ApiOnly}, {@link
 * WorkerOnly} or {@link ApiAndWorker}.
 */
public enum ApplicationRole {
    API,
    WORKER;

    private static final Profiles WORKER_PROFILE = Profiles.of("worker");

    public static ApplicationRole of(Environment environment) {
        return environment.acceptsProfiles(WORKER_PROFILE) ? WORKER : API;
    }

    static final class OnApi implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return of(context.getEnvironment()) == API;
        }
    }

    static final class OnWorker implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return of(context.getEnvironment()) == WORKER;
        }
    }
}
