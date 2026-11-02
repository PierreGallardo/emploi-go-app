package fr.emploigo.auth.infrastructure.purge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import fr.emploigo.auth.application.AuthenticationIntegrationTest;
import fr.emploigo.auth.application.purge.PurgeTemporaryRecordsUseCase;
import fr.emploigo.support.RowLock;
import fr.emploigo.support.SqlFailure;

import io.micrometer.core.instrument.MeterRegistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.dao.DataAccessException;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.stream.Stream;

import javax.sql.DataSource;

/**
 * Each kind of data is kept just before its retention and deleted just after, and a failing kind
 * does not block the others; the scheduler is tested in {@code WorkerInfrastructureIT}.
 */
class PurgeTemporaryRecordsUseCaseIT extends AuthenticationIntegrationTest {
    private static final String ADDRESS = "jeune@example.invalid";

    @Autowired MeterRegistry meters;
    @Autowired AutowireCapableBeanFactory beans;
    @Autowired DataSource dataSource;
    private PurgeTemporaryRecordsUseCase purge;

    @BeforeEach
    void worker() {
        purge = new PurgeTemporaryRecordsUseCase(WorkerPurge.repository(beans), clock, meters);
    }

    private PurgeTemporaryRecordsUseCase.Purged purgeAt(Instant instant) {
        clock.now = instant;
        return purge.purge();
    }

    private long account() {
        return jdbc.queryForObject(
                """
                INSERT INTO auth_accounts(
                    username, email_key, password_hash, email_verified, enabled)
                VALUES (?, ?, 'hash', TRUE, TRUE) RETURNING id
                """,
                Long.class,
                UUID.randomUUID().toString().replace("-", ""),
                UUID.randomUUID() + "@example.invalid");
    }

    private UUID registration(String status, Instant expiresAt) {
        UUID id = UUID.randomUUID();
        boolean pending = status.equals("PENDING");
        jdbc.update(
                """
                INSERT INTO auth_registrations(
                    id, email_key, password_hash, confirmation_key_hash, expires_at, status)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                id,
                pending ? id + "@example.invalid" : null,
                pending ? "slow-hash" : null,
                pending ? "key" : null,
                Timestamp.from(expiresAt),
                status);
        jdbc.update(
                """
                INSERT INTO auth_verifications(
                    id, registration_id, token_hash, key_id, expires_at, status, created_at)
                VALUES (?, ?, 'hash', 'key', ?, 'USED', ?)
                """,
                UUID.randomUUID(),
                id,
                Timestamp.from(expiresAt),
                Timestamp.from(expiresAt.minus(Duration.ofHours(24))));
        return id;
    }

    private void confirmedRegistration(Instant expiresAt) {
        registration("CONFIRMED", expiresAt);
    }

    private void expiredRegistration(Instant expiresAt) {
        registration("EXPIRED", expiresAt);
    }

    private void passwordReset(Instant expiresAt) {
        jdbc.update(
                """
                INSERT INTO auth_password_resets(
                    id, account_id, token_hash, key_id, credential_version, expires_at, status)
                VALUES (?, ?, 'hash', 'key', 0, ?, 'REPLACED')
                """,
                UUID.randomUUID(),
                account(),
                Timestamp.from(expiresAt));
    }

    private void googleFlow(Instant expiresAt) {
        jdbc.update(
                """
                INSERT INTO auth_google_flows(state_hash, browser_hash, expires_at)
                VALUES (?, ?, ?)
                """,
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                Timestamp.from(expiresAt));
    }

    private void googlePending(Instant expiresAt) {
        jdbc.update(
                """
                INSERT INTO auth_google_pending(
                    id, browser_hash, flow_hash, subject, email_key, authoritative_email,
                    expires_at)
                VALUES (?, 'browser', 'flow', 'subject', ?, TRUE, ?)
                """,
                UUID.randomUUID(),
                ADDRESS,
                Timestamp.from(expiresAt));
    }

    private void googleVerification(Instant expiresAt) {
        jdbc.update(
                """
                INSERT INTO auth_google_verifications(
                    id, subject, email_key, token_hash, key_id, status, expires_at)
                VALUES (?, ?, ?, 'hash', 'key', 'USED', ?)
                """,
                UUID.randomUUID(),
                UUID.randomUUID().toString(),
                ADDRESS,
                Timestamp.from(expiresAt));
    }

    private void reconnection(Instant expiresAt, boolean revoked) {
        jdbc.update(
                """
                INSERT INTO auth_reconnections(
                    id, account_id, credential_version, secret_hash, expires_at, revoked)
                VALUES (?, ?, 0, ?, ?, ?)
                """,
                UUID.randomUUID(),
                account(),
                "a".repeat(64),
                Timestamp.from(expiresAt),
                revoked);
    }

    private void liveReconnection(Instant expiresAt) {
        reconnection(expiresAt, false);
    }

    private void revokedReconnection(Instant expiresAt) {
        reconnection(expiresAt, true);
    }

    /** A kind of data that is deleted once its retention has passed since its expiry. */
    static Stream<Arguments> expiringData() {
        return Stream.of(
                argumentSet(
                        "confirmed registration and its links",
                        "auth_registrations",
                        Duration.ofDays(7),
                        (BiConsumer<PurgeTemporaryRecordsUseCaseIT, Instant>)
                                PurgeTemporaryRecordsUseCaseIT::confirmedRegistration),
                argumentSet(
                        "expired registration and its links",
                        "auth_registrations",
                        Duration.ofDays(7),
                        (BiConsumer<PurgeTemporaryRecordsUseCaseIT, Instant>)
                                PurgeTemporaryRecordsUseCaseIT::expiredRegistration),
                argumentSet(
                        "request to recover a password",
                        "auth_password_resets",
                        Duration.ofDays(7),
                        (BiConsumer<PurgeTemporaryRecordsUseCaseIT, Instant>)
                                PurgeTemporaryRecordsUseCaseIT::passwordReset),
                argumentSet(
                        "start of the Google authentication",
                        "auth_google_flows",
                        Duration.ofHours(23),
                        (BiConsumer<PurgeTemporaryRecordsUseCaseIT, Instant>)
                                PurgeTemporaryRecordsUseCaseIT::googleFlow),
                argumentSet(
                        "pending Google authentication",
                        "auth_google_pending",
                        Duration.ofHours(23),
                        (BiConsumer<PurgeTemporaryRecordsUseCaseIT, Instant>)
                                PurgeTemporaryRecordsUseCaseIT::googlePending),
                argumentSet(
                        "verification of the email of a Google identity",
                        "auth_google_verifications",
                        Duration.ofDays(7),
                        (BiConsumer<PurgeTemporaryRecordsUseCaseIT, Instant>)
                                PurgeTemporaryRecordsUseCaseIT::googleVerification),
                argumentSet(
                        "family of reconnection not revoked",
                        "auth_reconnections",
                        Duration.ZERO,
                        (BiConsumer<PurgeTemporaryRecordsUseCaseIT, Instant>)
                                PurgeTemporaryRecordsUseCaseIT::liveReconnection),
                argumentSet(
                        "family of reconnection revoked",
                        "auth_reconnections",
                        Duration.ZERO,
                        (BiConsumer<PurgeTemporaryRecordsUseCaseIT, Instant>)
                                PurgeTemporaryRecordsUseCaseIT::revokedReconnection));
    }

    @ParameterizedTest
    @MethodSource("expiringData")
    void dataIsKeptJustBeforeItsRetentionAndDeletedJustAfter(
            String table, Duration retention, BiConsumer<PurgeTemporaryRecordsUseCaseIT, Instant> insert) {
        Instant expiry = clock.instant();
        insert.accept(this, expiry);

        purgeAt(expiry.plus(retention).minusSeconds(1));
        assertThat(count(table)).as("rows of " + table + " just before the retention").isEqualTo(1);

        purgeAt(expiry.plus(retention).plusSeconds(1));
        assertThat(count(table)).as("rows of " + table + " just after the retention").isZero();
    }

    @Test
    void aRegistrationLinkIsDeletedWithItsRegistration() {
        Instant expiry = clock.instant();
        confirmedRegistration(expiry);

        purgeAt(expiry.plus(Duration.ofDays(7)).plusSeconds(1));

        assertThat(count("auth_verifications")).as("links").isZero();
    }

    @Test
    void aFamilyOfReconnectionThatHasNotExpiredIsKeptWhetherRevokedOrNot() {
        Instant now = clock.instant();
        liveReconnection(now.plusSeconds(1));
        revokedReconnection(now.plusSeconds(1));

        purgeAt(now);

        assertThat(count("auth_reconnections")).as("families").isEqualTo(2);
    }

    @Test
    void theSweepOfFamiliesLeavesOneThatWasRenewedWhileItWaited() throws Exception {
        Instant expiry = clock.instant();
        liveReconnection(expiry);
        UUID id = jdbc.queryForObject("SELECT id FROM auth_reconnections", UUID.class);
        Instant later = expiry.plusSeconds(1);

        try (var pool = Executors.newVirtualThreadPerTaskExecutor();
                var lock = RowLock.hold(dataSource, "auth_reconnections", id)) {
            var sweep = pool.submit(() -> purgeAt(later));
            RowLock.awaitWaiters(jdbc, 1, Duration.ofSeconds(5));
            lock.execute(
                    """
                    UPDATE auth_reconnections SET expires_at = '%s' WHERE id = '%s'
                    """
                            .formatted(later.plus(Duration.ofDays(30)), id));
            lock.commit();
            sweep.get(10, TimeUnit.SECONDS);
        }

        assertThat(count("auth_reconnections")).as("families").isEqualTo(1);
    }

    @Test
    void aPendingRegistrationLosesTheDataOfTheYoungPersonOnceItExpires() {
        Instant expiry = clock.instant();
        UUID id = registration("PENDING", expiry);

        purgeAt(expiry.plusSeconds(1));

        var row =
                jdbc.queryForMap(
                        """
                        SELECT status, email_key, password_hash, confirmation_key_hash
                        FROM auth_registrations
                        WHERE id = ?
                        """,
                        id);
        assertThat(row.get("status")).as("status").isEqualTo("EXPIRED");
        assertThat(row.values().stream().skip(1)).as("keys, hashes").containsOnlyNulls();
    }

    @Test
    void aPendingRegistrationThatHasNotExpiredIsLeftAlone() {
        Instant now = clock.instant();
        UUID id = registration("PENDING", now.plusSeconds(1));

        purgeAt(now);

        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM auth_registrations WHERE id = ?",
                                String.class,
                                id))
                .as("status")
                .isEqualTo("PENDING");
    }

    @Test
    void anExpiredPendingRegistrationIsDeletedAfterSevenDaysLikeTheOthers() {
        Instant expiry = clock.instant();
        registration("PENDING", expiry);

        purgeAt(expiry.plus(Duration.ofDays(7)).plusSeconds(1));

        assertThat(count("auth_registrations")).as("registrations").isZero();
    }

    @Test
    void aSecondRunHandlesNothing() {
        Instant expiry = clock.instant();
        googleFlow(expiry);
        purgeAt(expiry.plus(Duration.ofDays(30)));

        assertThat(purgeAt(expiry.plus(Duration.ofDays(30))).total())
                .as("rows handled by the second run")
                .isZero();
    }

    @Test
    void aKindThatFailsDoesNotKeepTheOthersFromBeingPurged() {
        Instant expiry = clock.instant();
        passwordReset(expiry);
        googleFlow(expiry);

        // The requests of recovery come before the starts of the Google authentication in the order
        // of the purge.
        try (var failure = SqlFailure.inject(jdbc, "auth_password_resets", "BEFORE DELETE")) {
            assertThatThrownBy(() -> purgeAt(expiry.plus(Duration.ofDays(8))))
                    .as("run")
                    .isInstanceOf(DataAccessException.class);
        }

        assertThat(count("auth_google_flows")).as("starts left").isZero();
        assertThat(count("auth_password_resets")).as("requests left").isEqualTo(1);
    }
}
