package fr.emploigo.auth.api.recovery;

import static fr.emploigo.support.HostCookies.RECONNECTION_COOKIE;
import static fr.emploigo.support.HostCookies.SESSION_COOKIE;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import fr.emploigo.auth.application.failure.AuthenticationFailedException;
import fr.emploigo.auth.application.session.AuthenticationGrant;
import fr.emploigo.auth.application.session.AuthenticationSessions;
import fr.emploigo.auth.application.session.PasswordAuthenticator;
import fr.emploigo.auth.application.session.ResumeSessionUseCase;
import fr.emploigo.auth.application.session.SessionPersistence;
import fr.emploigo.auth.domain.SessionIdentity;
import fr.emploigo.support.Browser;
import fr.emploigo.support.MailCounters;
import fr.emploigo.support.RowLock;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;

import javax.sql.DataSource;

class PasswordRecoveryConcurrencyIT extends PasswordRecoveryIntegrationTest {
    /**
     * Time for a request to reach the account lock; generous, since no lock_timeout runs during
     * this wait.
     */
    private static final Duration ARRIVAL = Duration.ofSeconds(5);

    @Autowired PasswordAuthenticator authenticator;
    @Autowired AuthenticationSessions sessions;
    @Autowired ResumeSessionUseCase resumeSession;
    @Autowired SessionRepository<?> sessionRepository;
    @Autowired DataSource dataSource;

    /**
     * Resumes a throwaway session once, so the timed resumes below do not pay for class loading and
     * JIT.
     */
    private void warmUpResume() throws Exception {
        resumeSession.resume(loggedIn().cookies.get(RECONNECTION_COOKIE));
    }

    @Test
    void concurrentConsumptionsCommitOnce() throws Exception {
        long id = owner();
        String link = requestedLink(EMAIL);
        var mail = new MailCounters();

        var statuses =
                overlappingAtTheRow(
                        "auth_accounts",
                        id,
                        () -> resetPassword(newBrowser(), link).statusCode(),
                        () -> resetPassword(newBrowser(), link).statusCode());

        assertThat(statuses).as("statuses").containsExactlyInAnyOrder(200, 410);
        assertThat(credentialVersion(id)).as("credential version").isEqualTo(1);
        assertThat(mail.calls()).as("changed notices sent").isEqualTo(1);
    }

    @Test
    void aLinkReplacedWhileItIsConsumedIsRefusedAndLeavesThePasswordUnchanged() throws Exception {
        long id = owner();
        String link = requestedLink(EMAIL);
        UUID resetId =
                jdbc.queryForObject(
                        "SELECT id FROM auth_password_resets WHERE account_id = ?", UUID.class, id);
        int status;

        // The reset reads the pending link, then waits on its row: a new request replaces it first.
        try (var pool = Executors.newVirtualThreadPerTaskExecutor();
                var lock = RowLock.hold(dataSource, "auth_password_resets", resetId)) {
            var resetting = pool.submit(() -> resetPassword(newBrowser(), link).statusCode());
            RowLock.awaitWaiters(jdbc, 1, ARRIVAL);
            lock.execute(
                    "UPDATE auth_password_resets SET status = 'REPLACED' WHERE id = '%s'"
                            .formatted(resetId));
            lock.commit();
            status = resetting.get();
        }

        assertThat(status).as("reset status").isEqualTo(410);
        assertThat(credentialVersion(id)).as("credential version").isZero();
    }

    @Test
    void passwordCheckedBeforeTheResetCannotCreateASessionAfterIt() throws Exception {
        long id = owner();
        // The check reads the credentials at version 0; the reset commits before the session is
        // created from them.
        var checked = authenticator.authenticate(EMAIL, original, "127.0.0.1");
        resetDone(requestedLink(EMAIL));

        assertThatThrownBy(
                        () -> sessions.create(checked, SessionPersistence.REMEMBERED, null, null))
                .as("session creation")
                .isInstanceOf(AuthenticationFailedException.class);
        assertThat(activeReconnections(id)).as("active reconnection families").isZero();
    }

    @Test
    void resumeQueuedBeforeTheResetGetsAGrantThatTheResetThenRejects() throws Exception {
        long id = owner();
        String cookie = loggedIn().cookies.get(RECONNECTION_COOKIE);
        String link = requestedLink(EMAIL);
        warmUpResume();
        AuthenticationGrant grant;
        int resetStatus;

        // PostgreSQL grants the row lock in arrival order: the resume queues first, so it takes its
        // grant before the reset commits.
        try (var pool = Executors.newVirtualThreadPerTaskExecutor();
                var lock = RowLock.hold(dataSource, "auth_accounts", id)) {
            var resume = pool.submit(() -> resumeSession.resume(cookie));
            RowLock.awaitWaiters(jdbc, 1, ARRIVAL);
            var resetting = pool.submit(() -> resetPassword(newBrowser(), link).statusCode());
            RowLock.awaitWaiters(jdbc, 2, ARRIVAL);
            lock.release();
            grant = resume.get();
            resetStatus = resetting.get();
        }

        assertThat(resetStatus).as("reset status").isEqualTo(200);
        assertThat(credentialVersion(id)).as("credential version").isEqualTo(1);
        assertThatThrownBy(
                        () ->
                                sessions.verify(
                                        grant.account().id(),
                                        grant.account().credentialVersion(),
                                        grant.account().familyId()))
                .as("verification of the grant")
                .isInstanceOf(AuthenticationFailedException.class);
        assertThatThrownBy(() -> resumeSession.resume(grant.reconnectionCookie()))
                .as("resume with the rotated cookie")
                .isInstanceOf(AuthenticationFailedException.class);
    }

    @Test
    void resumeQueuedAfterTheResetIsRejectedWithoutGrant() throws Exception {
        long id = owner();
        String cookie = loggedIn().cookies.get(RECONNECTION_COOKIE);
        String link = requestedLink(EMAIL);
        warmUpResume();
        int resetStatus;

        // PostgreSQL grants the row lock in arrival order: the reset queues first, so it commits
        // before the resume reads the account.
        try (var pool = Executors.newVirtualThreadPerTaskExecutor();
                var lock = RowLock.hold(dataSource, "auth_accounts", id)) {
            var resetting = pool.submit(() -> resetPassword(newBrowser(), link).statusCode());
            RowLock.awaitWaiters(jdbc, 1, ARRIVAL);
            var resume = pool.submit(() -> resumeSession.resume(cookie));
            RowLock.awaitWaiters(jdbc, 2, Duration.ofSeconds(1));
            lock.release();
            resetStatus = resetting.get();

            assertThatThrownBy(resume::get)
                    .as("resume")
                    .hasCauseInstanceOf(AuthenticationFailedException.class);
        }

        assertThat(resetStatus).as("reset status").isEqualTo(200);
        assertThat(credentialVersion(id)).as("credential version").isEqualTo(1);
    }

    /**
     * A session saved late, after the reset: the returned action saves it and gives its cookie to
     * the browser.
     */
    private <S extends Session> Runnable prepareLateSession(
            SessionRepository<S> repository, AuthenticationGrant grant, Browser browser) {
        S late = repository.createSession();
        var account = grant.account();
        late.setAttribute(
                SessionIdentity.ATTRIBUTE,
                new SessionIdentity(
                        account.id(),
                        account.credentialVersion(),
                        account.familyId(),
                        clock.instant(),
                        true));
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(
                        Long.toString(account.id()),
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_JEUNE"))));
        late.setAttribute("SPRING_SECURITY_CONTEXT", context);
        return () -> {
            repository.save(late);
            browser.cookies.put(
                    SESSION_COOKIE,
                    Base64.getEncoder()
                            .encodeToString(late.getId().getBytes(StandardCharsets.UTF_8)));
        };
    }

    @Test
    void lateSessionSaveCannotRestoreAccessAfterConcurrentResetsOfTheSameLink() throws Exception {
        long id = owner();
        String link = requestedLink(EMAIL);
        var grant =
                sessions.create(
                        authenticator.authenticate(EMAIL, original, "127.0.0.1"),
                        SessionPersistence.REMEMBERED,
                        null,
                        null);
        var old = newBrowser();
        Runnable lateSave = prepareLateSession(sessionRepository, grant, old);

        overlappingAtTheRow(
                "auth_accounts",
                id,
                () -> resetPassword(newBrowser(), link).statusCode(),
                () -> resetPassword(newBrowser(), link).statusCode());
        lateSave.run();

        assertThat(old.get("session").statusCode()).as("late session status").isEqualTo(401);
    }
}
