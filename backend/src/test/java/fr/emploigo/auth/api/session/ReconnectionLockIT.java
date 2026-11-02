package fr.emploigo.auth.api.session;

import static fr.emploigo.support.HostCookies.RECONNECTION_COOKIE;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import fr.emploigo.auth.application.failure.AuthenticationFailedException;
import fr.emploigo.auth.application.session.ResumeSessionUseCase;
import fr.emploigo.support.LogCapture;
import fr.emploigo.support.RowLock;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.net.http.HttpResponse;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

class ReconnectionLockIT extends AuthenticationSessionSupport {
    @Test
    void previousSecretAfterToleranceRevokesWithoutWaitingForTheAccountLock()
            throws Exception {
        var rotated = rotatedOnce();
        var thief = holding(rotated.previous());
        clock.now = clock.now.plus(TOLERANCE).plusSeconds(1);
        HttpResponse<String> response;

        try (var _ = RowLock.hold(dataSource, "auth_accounts", accountId)) {
            response = assertTimeoutPreemptively(WITHOUT_WAITING, () -> resume(thief));
        }

        assertThat(response.statusCode()).as("status").isEqualTo(401);
        assertThat(familyRevoked()).as("family revoked").isTrue();
    }

    @Test
    void resumeThatLosesARotationUnderTheLocksDoesNotRevokeTheFamily() throws Exception {
        String cookie = loggedIn().cookies.get(RECONNECTION_COOKIE);

        try (var pool = Executors.newVirtualThreadPerTaskExecutor();
                var logs = LogCapture.of(ResumeSessionUseCase.class);
                var lock = RowLock.hold(dataSource, "auth_accounts", accountId)) {
            // The right secret passes the check made without lock, then waits for the account lock
            // held by the test.
            var resuming = pool.submit(() -> resumeSession.resume(cookie));
            RowLock.awaitWaiters(jdbc, 1, ARRIVAL);
            // A rotation committed a minute ago, as far as the tolerance can tell: the secret of
            // the cookie is now the
            // previous one, and beyond the tolerance. Under the locks, it is still never a reason
            // to revoke.
            lock.execute(ROTATION + ", rotated_at = rotated_at - INTERVAL '1 minute'");
            lock.commit();

            assertRefused(resuming, AuthenticationFailedException.Reason.RECONNECTION_REFUSED);
            assertThat(familyRevoked()).as("family revoked").isFalse();
            assertThat(logs.events()).as("events").isEmpty();
        }
    }

    @Test
    void resumeThatLosesARotationUnderTheLocksWithinTheToleranceIsServed() throws Exception {
        String cookie = loggedIn().cookies.get(RECONNECTION_COOKIE);

        try (var pool = Executors.newVirtualThreadPerTaskExecutor();
                var logs = LogCapture.of(ResumeSessionUseCase.class);
                var lock = RowLock.hold(dataSource, "auth_accounts", accountId)) {
            // The right secret passes the check made without lock, then waits for the account lock
            // held by the test, which rotates the family meanwhile: under the locks, the secret of
            // the cookie is the previous one, replaced just now.
            var resuming = pool.submit(() -> resumeSession.resume(cookie));
            RowLock.awaitWaiters(jdbc, 1, ARRIVAL);
            lock.execute(ROTATION);
            lock.commit();

            assertThat(resuming.get(ARRIVAL.toSeconds(), TimeUnit.SECONDS))
                    .as("grant")
                    .isNotNull();
            assertThat(familyRevoked()).as("family revoked").isFalse();
            assertThat(logs.events()).as("events").isEmpty();
        }
    }

    static Stream<Arguments> changesOfTheFamilyBeforeTheRevocationOfAReplay() {
        return Stream.of(
                argumentSet("rotation concurrente", ROTATION + ", rotated_at = now()", false),
                argumentSet("révocation concurrente", REVOCATION, true));
    }

    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("changesOfTheFamilyBeforeTheRevocationOfAReplay")
    void previousSecretAfterToleranceDoesNotLogWhenAConcurrentChangeCommits(
            String change, boolean revoked) throws Exception {
        var rotated = rotatedOnce();
        UUID family =
                jdbc.queryForObject(
                        "SELECT id FROM auth_reconnections WHERE account_id = ?",
                        UUID.class,
                        accountId);
        clock.now = clock.now.plus(TOLERANCE).plusSeconds(1);

        try (var pool = Executors.newVirtualThreadPerTaskExecutor();
                var logs = LogCapture.of(ResumeSessionUseCase.class);
                var lock = RowLock.hold(dataSource, "auth_reconnections", family)) {
            // The replay is beyond the tolerance: its revocation waits for the family row held by
            // the test.
            var replaying = pool.submit(() -> resumeSession.resume(rotated.previous()));
            RowLock.awaitWaiters(jdbc, 1, ARRIVAL);
            lock.execute(change);
            lock.commit();

            assertRefused(replaying, AuthenticationFailedException.Reason.RECONNECTION_REFUSED);
            assertThat(familyRevoked()).as("family revoked").isEqualTo(revoked);
            assertThat(logs.events()).as("events").isEmpty();
        }
    }

    static Stream<Arguments> changesOfTheFamilyThatRefuseTheReconnection() {
        return Stream.of(
                argumentSet(
                        "secret tourné il y a plus que la tolérance",
                        ROTATION + ", rotated_at = rotated_at - INTERVAL '1 minute'",
                        AuthenticationFailedException.Reason.RECONNECTION_REFUSED),
                argumentSet(
                        "famille révoquée",
                        REVOCATION,
                        AuthenticationFailedException.Reason.RECONNECTION_REFUSED),
                argumentSet(
                        "famille expirée",
                        EXPIRY,
                        AuthenticationFailedException.Reason.RECONNECTION_REFUSED));
    }

    static Stream<Arguments> changesThatRefuseTheReconnection() {
        return Stream.concat(
                changesOfTheFamilyThatRefuseTheReconnection(),
                Stream.of(
                        argumentSet(
                                "compte désactivé",
                                """
                                    UPDATE auth_accounts SET enabled = FALSE
                                    """,
                                AuthenticationFailedException.Reason.RECONNECTION_REFUSED),
                        argumentSet(
                                "rotation et version des identifiants changées",
                                """
                                    UPDATE auth_reconnections
                                    SET previous_hash = secret_hash, secret_hash = 'rotated';
                                    UPDATE auth_accounts
                                    SET credential_version = credential_version + 1
                                    """,
                                AuthenticationFailedException.Reason.RECONNECTION_REFUSED),
                        argumentSet(
                                "version des identifiants changée",
                                """
                                    UPDATE auth_accounts
                                    SET credential_version = credential_version + 1
                                    """,
                                AuthenticationFailedException.Reason.RECONNECTION_REFUSED)));
    }

    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("changesThatRefuseTheReconnection")
    void resumeWithoutLocksIsRefusedWhenAChangeCommitsBeforeItsLocks(
            String change, AuthenticationFailedException.Reason reason)
            throws Exception {
        String cookie = loggedIn().cookies.get(RECONNECTION_COOKIE);

        try (var pool = Executors.newVirtualThreadPerTaskExecutor();
                var lock = RowLock.hold(dataSource, "auth_accounts", accountId)) {
            // The right secret passes the check made without lock, then waits for the account lock
            // held by the test.
            var resuming = pool.submit(() -> resumeSession.resume(cookie));
            RowLock.awaitWaiters(jdbc, 1, ARRIVAL);
            lock.execute(change);
            lock.commit();

            assertRefused(resuming, reason);
        }
    }

    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("changesOfTheFamilyThatRefuseTheReconnection")
    void resumeHoldingTheAccountLockIsRefusedWhenAChangeCommitsFirst(
            String change, AuthenticationFailedException.Reason reason)
            throws Exception {
        String cookie = loggedIn().cookies.get(RECONNECTION_COOKIE);
        UUID family =
                jdbc.queryForObject(
                        "SELECT id FROM auth_reconnections WHERE account_id = ?",
                        UUID.class,
                        accountId);

        try (var pool = Executors.newVirtualThreadPerTaskExecutor();
                var lock = RowLock.hold(dataSource, "auth_reconnections", family)) {
            // The resume takes the account lock, then waits for the family lock held by the test.
            var resuming = pool.submit(() -> resumeSession.resume(cookie));
            RowLock.awaitWaiters(jdbc, 1, ARRIVAL);
            lock.execute(change);
            lock.commit();

            assertRefused(resuming, reason);
        }
    }
}
