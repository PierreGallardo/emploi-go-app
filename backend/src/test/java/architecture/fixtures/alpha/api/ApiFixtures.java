package architecture.fixtures.alpha.api;

import architecture.fixtures.alpha.application.ApplicationFixtures;
import architecture.fixtures.alpha.domain.DomainFixtures;
import architecture.fixtures.alpha.infrastructure.InfrastructureFixtures;

import fr.emploigo.config.ApiAndWorker;
import fr.emploigo.config.ApiOnly;

import io.lettuce.core.RedisClient;
import io.swagger.v3.oas.annotations.responses.ApiResponse;

import jakarta.persistence.EntityManager;

import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;

public final class ApiFixtures {
    public static class Allowed {
        ApplicationFixtures.Allowed useCase;
        DomainFixtures.Offer mappedValue;
    }

    @RestController
    @ApiOnly
    public static class Reserved {}

    @RestController
    @ApiAndWorker
    public static class SharedController {}

    @RestController
    public static class Endpoint {}

    public static class WithInfrastructure {
        InfrastructureFixtures.Allowed adapter;
    }

    public static class WithJdbc {
        DataSource dataSource;
    }

    public static class WithJpaOperations {
        EntityManager entityManager;
    }

    @RestController
    @ApiResponse(responseCode = "400")
    public static class WithClassResponse {}

    @RestController
    @ApiResponse(responseCode = "400")
    @ApiResponse(responseCode = "503")
    public static class WithClassResponses {}

    @RestController
    public static class WithRouteResponsesOnly {
        @ApiResponse(responseCode = "400")
        public void route() {}
    }

    public static class WithSdk {
        RedisClient redis;
    }
}
