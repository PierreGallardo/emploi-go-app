package architecture.fixtures.alpha.application;

import architecture.fixtures.alpha.api.ApiFixtures;
import architecture.fixtures.alpha.domain.DomainFixtures;
import architecture.fixtures.alpha.infrastructure.InfrastructureFixtures;
import architecture.fixtures.beta.application.OtherModuleFixtures;

import io.lettuce.core.RedisClient;

import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

public final class ApplicationFixtures {
    @Service
    @Transactional
    public static class Allowed {
        DomainFixtures.Offer value;
    }

    public static class WithApi {
        ApiFixtures.Allowed controller;
    }

    public static class WithHttp {
        HttpStatus status;
    }

    public static class WithInfrastructure {
        InfrastructureFixtures.Allowed adapter;
    }

    public static class WithSpringData {
        org.springframework.data.domain.Pageable page;
    }

    public static class WithJpa {
        jakarta.persistence.EntityManager entityManager;
    }

    @jakarta.persistence.Entity
    public static class WithEntity {}

    public static class WithSdk {
        RedisClient redis;
    }

    public static class WithRedis {
        org.springframework.data.redis.core.StringRedisTemplate redis;
    }

    public static class UserDetailsServiceWithoutBean implements UserDetailsService {
        @Override
        public UserDetails loadUserByUsername(String username) {
            return null;
        }
    }

    public static class WithUserDetailsServiceBeanMethod {
        @Bean
        UserDetailsService service() {
            return new UserDetailsServiceWithoutBean();
        }
    }

    public static class WithAuthenticationProviderSubtypeBeanMethod {
        @Bean
        DaoAuthenticationProvider provider() {
            return new DaoAuthenticationProvider(new UserDetailsServiceWithoutBean());
        }
    }

    @Service
    public static class UserDetailsServiceComponent extends UserDetailsServiceWithoutBean {}

    @Service
    public static class AuthenticationProviderComponent implements AuthenticationProvider {
        @Override
        public Authentication authenticate(Authentication authentication) {
            return null;
        }

        @Override
        public boolean supports(Class<?> authentication) {
            return false;
        }
    }

    public static class WithPasswordEncoder {
        PasswordEncoder encoder;
    }

    public static class WithJdbcTemplate {
        org.springframework.jdbc.core.JdbcTemplate jdbc;
    }

    public static class WithTransaction {
        @Transactional
        void inTransaction() {}
    }

    /** What a counter adapter must be: no database, no transaction. */
    public static class Counter {
        InfrastructureFixtures.Allowed adapter;
    }

    public static class QuotaCounter {
        public void check() {}
    }

    public static class Gate {
        QuotaCounter quota;

        public void admit() {
            quota.check();
        }
    }

    public static class TransactionalCallingQuota {
        QuotaCounter quota;

        @Transactional
        void act() {
            quota.check();
        }
    }

    public static class TransactionalCallingQuotaThroughAnotherBean {
        Gate gate;

        @Transactional
        void act() {
            gate.admit();
        }
    }

    @Transactional
    public static class TransactionalClassCallingQuota {
        QuotaCounter quota;

        void act() {
            quota.check();
        }
    }

    public static class CountingBeforeTheTransaction {
        Gate gate;

        void act() {
            gate.admit();
        }
    }

    public static class TransactionalWithoutQuota {
        @Transactional
        void act() {}
    }

    public static class TransactionalCycle {
        @Transactional
        void act() {
            again();
        }

        void again() {
            act();
        }
    }

    public static class Relay {
        Gate gate;

        public void pass() {
            gate.admit();
        }
    }

    public static class TransactionalCallingQuotaInTwoHops {
        Relay relay;

        @Transactional
        void act() {
            relay.pass();
        }
    }

    public static class TransactionalCycleReachingQuota {
        QuotaCounter quota;

        @Transactional
        void act() {
            again();
        }

        void again() {
            act();
            quota.check();
        }
    }

    public interface LimiterPort {
        void admit();
    }

    public static class RedisLimiter implements LimiterPort {
        org.springframework.data.redis.core.StringRedisTemplate redis;

        @Override
        public void admit() {}
    }

    public static class TransactionalCallingRedisPort {
        LimiterPort port;

        @Transactional
        void act() {
            port.admit();
        }
    }

    public static class TransactionalCallingRedisAdapter {
        RedisLimiter limiter;

        @Transactional
        void act() {
            limiter.admit();
        }
    }

    public static class TransactionalCallingRedisTemplate {
        org.springframework.data.redis.core.StringRedisTemplate redis;

        @Transactional
        void act() {
            redis.hasKey("key");
        }
    }

    public interface PlainPort {
        void run();
    }

    public static class PlainImplementation implements PlainPort {
        @Override
        public void run() {}
    }

    public static class TransactionalCallingPlainPort {
        PlainPort port;

        @Transactional
        void act() {
            port.run();
        }
    }

    public static class Failure extends RuntimeException {
        public static Failure refusal() {
            return new Failure();
        }
    }

    public static class FailureFactory {
        public static Failure unavailable() {
            return new Failure();
        }
    }

    public static class Cycle {
        OtherModuleFixtures.Cycle other;
    }
}
