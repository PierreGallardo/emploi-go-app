package fr.emploigo.auth.infrastructure.purge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import fr.emploigo.auth.application.AuthenticationIntegrationTest;
import fr.emploigo.auth.application.purge.PurgeTemporaryRecordsUseCase;
import fr.emploigo.support.Containers;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.TransactionException;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * The grants mirror docs/backend/donnees.md: change one, change the other. A column right limits
 * what the role reads or writes, not the value it writes.
 */
class PurgeRolePrivilegesIT extends AuthenticationIntegrationTest {
    private static final String ROLE = "purge_worker_test";
    private static final String PASSWORD = "purge-worker-test-only";
    private static final List<String> GRANTS =
            List.of(
                    """
                    GRANT SELECT (id, status, expires_at),
                          UPDATE (status, email_key, password_hash, confirmation_key_hash),
                          DELETE ON auth_registrations TO %s
                    """
                            .formatted(ROLE),
                    """
                    GRANT SELECT (id, expires_at), DELETE ON auth_password_resets TO %s
                    """
                            .formatted(ROLE),
                    """
                    GRANT SELECT (state_hash, expires_at), DELETE ON auth_google_flows TO %s
                    """
                            .formatted(ROLE),
                    """
                    GRANT SELECT (id, expires_at), DELETE ON auth_google_pending TO %s
                    """
                            .formatted(ROLE),
                    """
                    GRANT SELECT (id, expires_at), DELETE ON auth_google_verifications TO %s
                    """
                            .formatted(ROLE),
                    """
                    GRANT SELECT (id, expires_at), DELETE ON auth_reconnections TO %s
                    """
                            .formatted(ROLE));

    private JdbcTemplate worker;
    private DriverManagerDataSource workerConnections;
    private LocalContainerEntityManagerFactoryBean workerEntities;

    @BeforeEach
    void aRoleWithTheRightsOfTheWorker() {
        // A role belongs to the whole cluster: one left by a run that stopped halfway must not fail
        // this one.
        dropTheRole();
        jdbc.execute(
                """
                CREATE ROLE %s LOGIN PASSWORD '%s'
                """
                        .formatted(ROLE, PASSWORD));
        GRANTS.forEach(jdbc::execute);
        var postgres = Containers.postgres();
        worker =
                new JdbcTemplate(
                        new DriverManagerDataSource(postgres.getJdbcUrl(), ROLE, PASSWORD));
    }

    @AfterEach
    void dropTheRole() {
        jdbc.execute(
                """
                DO $$ BEGIN
                    IF EXISTS (SELECT FROM pg_roles WHERE rolname = '%s') THEN
                        DROP OWNED BY %s;
                        DROP ROLE %s;
                    END IF;
                END $$
                """
                        .formatted(ROLE, ROLE, ROLE));
    }

    @Test
    void thePurgeRunsWithTheRightsOfTheWorkerAndDeletesALinkWithItsRegistration() {
        Instant expiry = clock.instant();
        UUID registration = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO auth_registrations(id, expires_at, status)
                VALUES (?, ?, 'EXPIRED')
                """,
                registration,
                Timestamp.from(expiry));
        jdbc.update(
                """
                INSERT INTO auth_verifications(
                    id, registration_id, token_hash, key_id, expires_at, status, created_at)
                VALUES (?, ?, 'hash', 'key', ?, 'USED', ?)
                """,
                UUID.randomUUID(),
                registration,
                Timestamp.from(expiry),
                Timestamp.from(expiry));
        clock.now = expiry.plus(Duration.ofDays(8));
        var meters = new SimpleMeterRegistry();

        new PurgeTemporaryRecordsUseCase(purgeAsTheWorker(), clock, meters).purge();

        assertThat(count("auth_registrations")).as("registrations").isZero();
        assertThat(count("auth_verifications")).as("links").isZero();
    }

    @Test
    void thePurgeExpiresAPendingRegistrationAndClearsItsKeyWithTheRightsOfTheWorker() {
        Instant past = clock.instant().minus(Duration.ofHours(1));
        UUID registration = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO auth_registrations(
                    id, email_key, password_hash, confirmation_key_hash, expires_at, status)
                VALUES (?, 'jeune@example.invalid', 'hash', 'key', ?, 'PENDING')
                """,
                registration,
                Timestamp.from(past));
        var meters = new SimpleMeterRegistry();

        new PurgeTemporaryRecordsUseCase(purgeAsTheWorker(), clock, meters).purge();

        var row =
                jdbc.queryForMap(
                        """
                        SELECT status, email_key, password_hash, confirmation_key_hash
                        FROM auth_registrations WHERE id = ?
                        """,
                        registration);
        assertThat(row.get("status")).as("status").isEqualTo("EXPIRED");
        assertThat(row.get("email_key")).as("email").isNull();
        assertThat(row.get("password_hash")).as("password hash").isNull();
        assertThat(row.get("confirmation_key_hash")).as("key of the page").isNull();
    }

    /**
     * The purge with the rights of the worker: its entities take their connections from the role,
     * as the worker does in production.
     */
    private JpaAuthenticationPurgeRepository purgeAsTheWorker() {
        workerConnections =
                new DriverManagerDataSource(Containers.postgres().getJdbcUrl(), ROLE, PASSWORD);
        workerEntities = new LocalContainerEntityManagerFactoryBean();
        workerEntities.setDataSource(workerConnections);
        // The purge owns no entity: scanning its package only makes a persistence unit exist.
        workerEntities.setPackagesToScan("fr.emploigo.auth.infrastructure.purge");
        workerEntities.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        workerEntities.afterPropertiesSet();
        var factory = workerEntities.getObject();
        return new JpaAuthenticationPurgeRepository(
                SharedEntityManagerCreator.createSharedEntityManager(factory),
                new JpaTransactionManager(factory));
    }

    @AfterEach
    void closeTheWorkerEntities() {
        if (workerEntities == null) return;
        workerEntities.destroy();
        workerEntities = null;
    }

    @Test
    void aWorkerThatCannotReachTheDatabaseFailsThePurgeWithATransactionError() {
        var purge =
                new PurgeTemporaryRecordsUseCase(purgeAsTheWorker(), clock, new SimpleMeterRegistry());
        workerConnections.setUrl("jdbc:postgresql://127.0.0.1:1/unreachable");

        assertThatThrownBy(purge::purge).as("purge").isInstanceOf(TransactionException.class);
    }

    static Stream<Arguments> whatTheWorkerMustNotDo() {
        return Stream.of(
                argumentSet(
                        "read the secret of a family of reconnection",
                        "SELECT secret_hash FROM auth_reconnections"),
                argumentSet(
                        "read the address of a pending registration",
                        "SELECT email_key FROM auth_registrations"),
                argumentSet(
                        "read the hash of a password of a registration",
                        "SELECT password_hash FROM auth_registrations"),
                argumentSet("read the accounts", "SELECT * FROM auth_accounts"),
                argumentSet("read the sessions", "SELECT * FROM spring_session"),
                argumentSet("read the links", "SELECT * FROM auth_verifications"),
                argumentSet("change an account", "UPDATE auth_accounts SET enabled = FALSE"),
                argumentSet("delete an account", "DELETE FROM auth_accounts"),
                argumentSet("create a table", "CREATE TABLE intruder(id INTEGER)"),
                argumentSet("drop a table", "DROP TABLE auth_reconnections"));
    }

    @ParameterizedTest
    @MethodSource("whatTheWorkerMustNotDo")
    void theRoleOfTheWorkerCannot(String statement) {
        assertThatThrownBy(() -> worker.execute(statement))
                .as("statement")
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageMatching("(?s).*(permission denied|must be owner).*");
    }
}
