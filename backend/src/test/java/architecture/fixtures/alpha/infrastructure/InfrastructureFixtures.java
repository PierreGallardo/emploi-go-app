package architecture.fixtures.alpha.infrastructure;

import architecture.fixtures.alpha.api.ApiFixtures;
import architecture.fixtures.alpha.application.ApplicationFixtures;
import architecture.fixtures.alpha.domain.DomainFixtures;

import fr.emploigo.auth.domain.AuthenticationSecrets;
import fr.emploigo.auth.infrastructure.quota.QuotaKeys;

import io.lettuce.core.RedisClient;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.repository.Repository;

import javax.sql.DataSource;

public final class InfrastructureFixtures {
    public static class Allowed {
        DomainFixtures.Offer value;
        DataSource dataSource;
        RedisClient redis;
        ApplicationFixtures.Allowed workerUseCase;
    }

    @Entity
    public static class OfferJpaEntity {
        @Id Long id;
    }

    @Entity
    public static class OfferRow {
        @Id Long id;
    }

    public interface SpringDataOfferRepository extends Repository<OfferJpaEntity, Long> {}

    public static class OfferUseCase {}

    @Configuration
    public static class Wiring {}

    public static class WithApi {
        ApiFixtures.Allowed controller;
    }

    public static class WithApplicationException {
        void act() {
            throw new ApplicationFixtures.Failure();
        }
    }

    public static class WithApplicationExceptionFactory {
        void act() {
            throw ApplicationFixtures.Failure.refusal();
        }
    }

    public static class WithApplicationExceptionFromAnotherFactory {
        void act() {
            throw ApplicationFixtures.FailureFactory.unavailable();
        }
    }

    public static class WithDomainFailure {
        void act() {
            throw new DomainFixtures.Failure();
        }
    }

    public static class CatchingAnApplicationException {
        void act(Runnable action) {
            try {
                action.run();
            } catch (ApplicationFixtures.Failure refused) {
                refused.getMessage();
            }
        }
    }

    public static class WithRedisAndPlainDigest {
        StringRedisTemplate redis;
        AuthenticationSecrets secrets;
    }

    public static class WithRedisAndKeyedDigest {
        StringRedisTemplate redis;
        QuotaKeys keys;
    }
}
