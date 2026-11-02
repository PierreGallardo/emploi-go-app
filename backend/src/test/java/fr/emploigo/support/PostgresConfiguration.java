package fr.emploigo.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * {@code destroyMethod = ""} keeps a closing Spring context from stopping the shared PostgreSQL
 * container, which other cached contexts still use.
 */
@TestConfiguration(proxyBeanMethods = false)
public class PostgresConfiguration {
    @Bean(destroyMethod = "")
    @ServiceConnection
    PostgreSQLContainer postgres() {
        return Containers.postgres();
    }
}
