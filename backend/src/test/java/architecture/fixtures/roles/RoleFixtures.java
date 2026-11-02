package architecture.fixtures.roles;

import fr.emploigo.config.ApiAndWorker;
import fr.emploigo.config.ApiOnly;
import fr.emploigo.config.WorkerOnly;

import jakarta.servlet.http.HttpSession;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.repository.Repository;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.RestController;

public final class RoleFixtures {
    @Component
    @ApiOnly
    public static class ReservedToTheApi {}

    @Service
    @WorkerOnly
    public static class ReservedToTheWorker {}

    @Configuration
    @ApiAndWorker
    public static class Shared {}

    @ApiOnly
    public interface ReservedRepository extends Repository<Object, Long> {}

    @WorkerOnly
    @ConfigurationProperties("fixture")
    public record ReservedProperties(String value) {}

    @Component
    @ApiOnly
    @Profile("local")
    public static class ReservedToTheLocalApi {}

    @Component
    @ApiAndWorker
    public static class SharedWithPlainDependency {
        String value;
    }

    @Component
    @WorkerOnly
    public static class WorkerWithWorkerDependency {
        ReservedToTheWorker worker;
    }

    @Component
    @ApiAndWorker
    public static class SharedWithRedis {
        RedisTemplate<String, String> redis;
    }

    @Component
    @WorkerOnly
    public static class WorkerWithRedis {
        RedisTemplate<String, String> redis;
    }

    @Component
    @ApiAndWorker
    public static class SharedWithSession {
        HttpSession session;
    }

    @RestController
    @ApiAndWorker
    public static class SharedWithAControllerAnnotation {}

    @Component
    @ApiAndWorker
    public static class SharedWithAnApiClass {
        ReservedToTheApi api;
    }

    @Component
    @WorkerOnly
    public static class WorkerWithAnApiClass {
        ReservedToTheApi api;
    }

    @Component
    @WorkerOnly
    public static class WithAnApiMethod {
        @ApiOnly
        void method() {}
    }

    @Component
    public static class WithoutRole {}

    @Component
    @ApiOnly
    @WorkerOnly
    public static class WithTwoRoles {}

    @Component
    @ApiOnly
    @Profile("networker")
    public static class ReservedToAProfileThatMerelyContainsTheWord {}

    @Component
    @ApiOnly
    @Profile({"local", "worker"})
    public static class WithWorkerProfile {}

    @Component
    @ApiOnly
    @Profile("!worker")
    public static class WithNegatedWorkerProfile {}

    @Configuration
    @ApiAndWorker
    public static class WithWorkerProfileOnABean {
        @Bean
        @Profile("worker")
        Object bean() {
            return new Object();
        }
    }

    public interface RepositoryWithoutRole extends Repository<Object, Long> {}

    @ConfigurationProperties("fixture")
    public record PropertiesWithoutRole(String value) {}
}
