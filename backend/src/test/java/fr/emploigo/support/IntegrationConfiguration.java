package fr.emploigo.support;

import fr.emploigo.auth.application.AuthenticationTestSupport;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistrar;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * Redis is wired by properties, not {@code @ServiceConnection}: the container connection details of
 * Spring Boot carry no password, and the tests keep a password-protected Redis like the production
 * one.
 */
@TestConfiguration(proxyBeanMethods = false)
@Import(PostgresConfiguration.class)
public class IntegrationConfiguration {
    private static final String ORIGIN = "http://localhost:5173";

    @Bean
    @Primary
    TestClock testClock() {
        return new TestClock();
    }

    @Bean
    DynamicPropertyRegistrar infrastructureProperties() {
        byte[] tokenKey = new byte[32];
        new SecureRandom().nextBytes(tokenKey);
        String confirmationKey = Base64.getEncoder().encodeToString(tokenKey);
        return registry -> {
            var redis = Containers.redis();
            registry.add("spring.data.redis.host", redis::getHost);
            registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
            registry.add("spring.data.redis.password", () -> Containers.REDIS_PASSWORD);
            registry.add("management.server.port", () -> 0);
            registry.add("emploi-go.registration.mail-mode", () -> "local");
            registry.add("emploi-go.registration.token-key", () -> confirmationKey);
            registry.add("emploi-go.auth.quota-key", () -> AuthenticationTestSupport.QUOTA_KEY);
            registry.add("emploi-go.registration.public-origin", () -> ORIGIN);
            registry.add(
                    "emploi-go.registration.local-mail-url",
                    () -> FakeProviders.origin() + "/emails");
            registry.add("emploi-go.auth.password-hashing-iterations", () -> 1_000);
            registry.add("spring.datasource.hikari.connection-timeout", () -> 1_000);
            // Not the number of cores of the machine: the concurrent tests (two resets at once) and
            // the tests of the limit
            // (more calls than the limit) must not depend on it.
            registry.add("emploi-go.auth.password-hashing-max-concurrency", () -> 4);
            registry.add("emploi-go.google.enabled", () -> true);
            registry.add("emploi-go.google.client-id", () -> FakeGoogle.CLIENT_ID);
            registry.add("emploi-go.google.client-secret", () -> FakeGoogle.CLIENT_SECRET);
            registry.add(
                    "emploi-go.google.redirect-uri",
                    () -> ORIGIN + "/api/auth/google/callback/google");
            registry.add(
                    "emploi-go.google.authorization-uri",
                    () -> FakeProviders.origin() + "/authorize");
            registry.add("emploi-go.google.token-uri", () -> FakeProviders.origin() + "/token");
            registry.add("emploi-go.google.jwk-set-uri", () -> FakeProviders.origin() + "/jwks");
            registry.add("emploi-go.google.issuer-uri", FakeProviders::origin);
        };
    }
}
