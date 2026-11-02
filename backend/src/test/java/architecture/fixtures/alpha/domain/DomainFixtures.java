package architecture.fixtures.alpha.domain;

import architecture.fixtures.alpha.api.ApiFixtures;
import architecture.fixtures.alpha.application.ApplicationFixtures;
import architecture.fixtures.alpha.infrastructure.InfrastructureFixtures;

import io.lettuce.core.RedisClient;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Id;
import jakarta.validation.Validator;
import jakarta.validation.constraints.NotBlank;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;

public final class DomainFixtures {
    public static class Failure extends RuntimeException {}

    /** A business value: validation annotations are allowed, a persistence mapping is not. */
    public static class Offer {
        @NotBlank String label;
    }

    @Entity
    public static class MappedEntity {
        @Id Long id;
        @NotBlank String label;
    }

    public static class WithPersistenceAnnotation {
        @Column String label;
    }

    public static class WithApplication {
        ApplicationFixtures.Allowed useCase;
    }

    public static class WithInfrastructure {
        InfrastructureFixtures.Allowed adapter;
    }

    public static class WithApi {
        ApiFixtures.Allowed controller;
    }

    @Service
    public static class WithSpring {}

    public static class WithEntityManager {
        EntityManager entityManager;
    }

    public static class WithValidator {
        Validator validator;
    }

    public static class WithJdbc {
        DataSource dataSource;
    }

    public static class WithHttp {
        HttpStatus status;
    }

    public static class WithSdk {
        RedisClient redis;
    }
}
