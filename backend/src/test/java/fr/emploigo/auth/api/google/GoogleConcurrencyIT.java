package fr.emploigo.auth.api.google;

import static fr.emploigo.support.FakeGoogle.TOKEN_CALLS;
import static fr.emploigo.support.FakeGoogle.TOKEN_RESPONSE;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import fr.emploigo.auth.api.GoogleBrowser;
import fr.emploigo.auth.api.GoogleBrowser.Flow;
import fr.emploigo.auth.application.failure.AuthenticationFailedException;
import fr.emploigo.auth.application.google.GoogleAuthenticationFailedException;
import fr.emploigo.auth.application.session.AuthenticationGrant;
import fr.emploigo.auth.application.session.Credentials;
import fr.emploigo.auth.application.session.SessionPersistence;
import fr.emploigo.auth.domain.AuthenticationSecrets;
import fr.emploigo.auth.domain.GoogleIdentity;
import fr.emploigo.auth.domain.SessionIdentity;
import fr.emploigo.support.FakeGoogle;
import fr.emploigo.support.Stall;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

class GoogleConcurrencyIT extends GoogleAuthenticationSupport {
    @Test
    void concurrentSignupFinalizationOfOnePendingCreatesOneAccount() throws Exception {
        var pending = acceptedCallback().callback();

        String browser =
                jdbc.queryForObject(
                        """
                        SELECT f.browser_hash FROM auth_google_flows f
                        JOIN auth_google_pending p ON p.flow_hash = f.state_hash WHERE p.id = ?
                        """,
                        String.class,
                        pending.pendingId());

        var outcomes =
                arrivingOneAfterAnotherAt(
                        lockKey("google:browser:" + browser),
                        () -> outcome("CREATED", () -> signup(pending, "First")),
                        () -> outcome("CREATED", () -> signup(pending, "Second")));

        assertThat(outcomes)
                .as("one signup created the account, the other lost the flow")
                .containsExactly("CREATED", "INVALID_FLOW");
        assertThat(count("auth_accounts")).as("accounts").isEqualTo(1);
        assertThat(count("auth_google_identities")).as("linked identities").isEqualTo(1);
    }

    /** A local registration of {@code shared@gmail.com} and a Google flow for the same email. */
    private record SharedEmail(
            Callable<HttpResponse<String>> local, Callable<HttpResponse<String>> google) {}

    private SharedEmail sharedEmail() throws Exception {
        FakeGoogle.email = "shared@gmail.com";
        FakeGoogle.subject = "shared-subject";
        var local = newBrowser();
        var registration =
                local.post(
                        "signup",
                        Map.of(
                                "username",
                                "LocalOwner",
                                "email",
                                FakeGoogle.email,
                                "password",
                                password,
                                "confirmation",
                                password,
                                "acceptedTerms",
                                true,
                                "rememberMe",
                                false));
        assertThat(registration.statusCode()).as("given: registration accepted").isEqualTo(202);
        UUID operation =
                jdbc.queryForObject(
                        """
                        SELECT v.id FROM auth_verifications v
                        JOIN auth_registrations r ON r.id = v.registration_id WHERE r.email_key = ?
                        """,
                        UUID.class,
                        FakeGoogle.email);
        var google = pendingBrowser();
        return new SharedEmail(
                () -> local.post("signup/confirm", confirmation(local, operation)),
                () -> google.post("google/signup", Map.of("username", "GoogleOwner")));
    }

    @Test
    void localConfirmationBeforeGoogleSignupOfOneEmailCreatesOneAccount() throws Exception {
        var flows = sharedEmail();

        var local = flows.local().call();
        var google = flows.google().call();

        assertThat(local.statusCode()).as("local confirmation status").isEqualTo(200);
        assertThat(google.statusCode()).as("google signup status").isEqualTo(409);
        assertThat(code(google))
                .as("google signup error code: the email has an account")
                .isEqualTo("ACCOUNT_ACCESS_REQUIRED");
        assertThat(count("auth_accounts")).as("accounts").isEqualTo(1);
        assertThat(inconsistentAccounts()).as("inconsistent accounts").isZero();
    }

    @Test
    void googleSignupBeforeLocalConfirmationOfOneEmailCreatesOneAccount() throws Exception {
        var flows = sharedEmail();

        var google = flows.google().call();
        var local = flows.local().call();

        assertThat(google.statusCode()).as("google signup status").isEqualTo(200);
        assertThat(local.statusCode()).as("local confirmation status").isEqualTo(400);
        assertThat(code(local))
                .as("local confirmation error code: the registration is closed")
                .isEqualTo("INVALID_CODE");
        assertThat(count("auth_accounts")).as("accounts").isEqualTo(1);
        assertThat(inconsistentAccounts()).as("inconsistent accounts").isZero();
    }

    @Test
    void simultaneousLocalConfirmationAndGoogleSignupOfOneEmailCreateOneAccount()
            throws Exception {
        var flows = sharedEmail();

        var calls = List.of(flows.local(), flows.google());

        var responses = inParallel(calls.size(), index -> calls.get(index).call());

        assertThat(statuses(responses))
                .as("statuses: one flow creates the account, the other is refused")
                .hasSize(2)
                .containsOnlyOnce(200)
                // 409: Google finds the email taken; 400: Google closed the registration first.
                .containsAnyOf(409, 400);
        assertThat(count("auth_accounts")).as("accounts").isEqualTo(1);
        assertThat(inconsistentAccounts()).as("inconsistent accounts").isZero();
    }

    @Test
    void concurrentGoogleSignupsOfTwoSubjectsWithOneEmailCreateOneAccount() throws Exception {
        FakeGoogle.email = "shared@gmail.com";
        FakeGoogle.subject = "first-subject";
        var first = pendingBrowser();
        FakeGoogle.subject = "second-subject";
        var second = pendingBrowser();

        List<Callable<HttpResponse<String>>> signups =
                List.of(
                        () -> first.post("google/signup", Map.of("username", "FirstOwner")),
                        () -> second.post("google/signup", Map.of("username", "SecondOwner")));

        var responses = inParallel(signups.size(), index -> signups.get(index).call());

        assertThat(statuses(responses)).as("statuses").containsExactlyInAnyOrder(200, 409);
        assertThat(codes(responses))
                .as("error codes: the winner has none, the loser found the email taken")
                .containsExactlyInAnyOrder("", "ACCOUNT_ACCESS_REQUIRED");
        assertThat(count("auth_accounts")).as("accounts").isEqualTo(1);
        assertThat(count("auth_google_identities")).as("linked identities").isEqualTo(1);
    }

    @Test
    void concurrentGoogleSignupsOfOneSubjectWithTwoEmailsCreateOneAccountAndOneIdentity()
            throws Exception {
        FakeGoogle.email = "first@gmail.com";
        var first = pendingBrowser();
        FakeGoogle.email = "second@gmail.com";
        var second = pendingBrowser();

        List<Callable<HttpResponse<String>>> signups =
                List.of(
                        () -> first.post("google/signup", Map.of("username", "FirstOwner")),
                        () -> second.post("google/signup", Map.of("username", "SecondOwner")));

        var responses = inParallel(signups.size(), index -> signups.get(index).call());

        assertThat(statuses(responses)).as("statuses").containsExactlyInAnyOrder(200, 409);
        assertThat(codes(responses))
                .as("error codes: the loser found the subject linked")
                .containsExactlyInAnyOrder("", "ALREADY_LINKED");
        assertThat(count("auth_accounts")).as("accounts").isEqualTo(1);
        assertThat(count("auth_google_identities")).as("linked identities").isEqualTo(1);
    }

    @Test
    void concurrentConfirmationsOfOneEmailLinkConsumeItOnce() throws Exception {
        String link = requestedEmailLink(pendingExternalBrowser());

        Callable<Integer> confirmation =
                () -> newBrowser().post("google/email/confirm", Map.of("token", link)).statusCode();
        var statuses = inParallel(2, index -> confirmation.call());

        assertThat(statuses)
                .as("one confirmation consumed the link, the other found it used")
                .containsExactlyInAnyOrder(204, 410);
        assertThat(count("auth_accounts")).as("accounts").isZero();
        assertThat(
                        jdbc.queryForList(
                                "SELECT status FROM auth_google_verifications WHERE subject = ?",
                                String.class,
                                FakeGoogle.subject))
                .as("verification statuses")
                .containsExactly("USED");
    }

    @Test
    void simultaneousEmailRequestsOfOneSubjectLeaveOnePendingVerification() throws Exception {
        var first = pendingExternalBrowser();
        var second = pendingExternalBrowser();
        List<Callable<HttpResponse<String>>> requests =
                List.of(
                        () -> first.post("google/email", Map.of()),
                        () -> second.post("google/email", Map.of()));

        var responses = inParallel(requests.size(), index -> requests.get(index).call());

        assertThat(statuses(responses))
                .as("statuses: accepted, refused by the delay between two emails, or asked again")
                .isSubsetOf(202, 429, 503);
        assertThat(pendingVerifications()).as("pending verifications of the subject").isEqualTo(1);
    }

    private record ExchangeInFlight<T>(HttpResponse<String> callback, T during) {}

    /**
     * Holds the callback of {@code flow} at the token endpoint while {@code during} runs; it ends
     * within {@link Stall#GATE_SECONDS} whatever happens.
     */
    private <T> ExchangeInFlight<T> whileExchangeIsInFlight(
            GoogleBrowser browser, Flow flow, Callable<T> during) throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor();
                var hold = TOKEN_RESPONSE.hold()) {
            var exchange = executor.submit(() -> browser.callback(flow, false));
            hold.awaitRequests(1);
            T result = during.call();
            hold.release();
            return new ExchangeInFlight<>(exchange.get(10, TimeUnit.SECONDS), result);
        }
    }

    @Test
    void callbackReplayedWhileFirstExchangeIsInFlightDoesNotCallTheTokenEndpointAgain()
            throws Exception {
        var browser = newBrowser();
        var flow = browser.start();
        var sameBrowser = sharingCookiesOf(browser);
        var tokenCalls = new Calls(TOKEN_CALLS);

        var held = whileExchangeIsInFlight(browser, flow, () -> sameBrowser.callback(flow, false));

        assertThat(held.during().statusCode()).as("replay status").isEqualTo(302);
        assertThat(tokenCalls.count())
                .as("token endpoint calls: only the first exchange")
                .isEqualTo(1);
    }

    @Test
    void newStartWhileAnExchangeIsInFlightSupersedesItAndCreatesNoPendingNorAccount()
            throws Exception {
        var browser = newBrowser();
        var flow = browser.start();
        var sameBrowser = sharingCookiesOf(browser);

        var held = whileExchangeIsInFlight(browser, flow, sameBrowser::start);

        assertThat(held.callback().statusCode())
                .as("status of the superseded callback")
                .isEqualTo(302);
        assertThat(count("auth_google_pending")).as("pending flows").isZero();
        assertThat(count("auth_accounts")).as("accounts").isZero();
        assertThat(browser.get("session").statusCode()).as("session status").isEqualTo(401);
    }

    static Stream<Arguments> changesThatEndAFlowOfAnAccount() {
        return Stream.of(
                argumentSet(
                        "version incrémentée seule",
                        "UPDATE auth_accounts SET credential_version = credential_version + 1"
                                + " WHERE id = ?"),
                argumentSet(
                        "famille révoquée seule",
                        "UPDATE auth_reconnections SET revoked = TRUE WHERE account_id = ?"),
                argumentSet(
                        "compte désactivé seul",
                        "UPDATE auth_accounts SET enabled = FALSE WHERE id = ?"),
                argumentSet(
                        "adresse non vérifiée seule",
                        "UPDATE auth_accounts SET email_verified = FALSE WHERE id = ?"),
                argumentSet(
                        "compte administrateur seul",
                        "UPDATE auth_accounts SET role = 'ADMIN' WHERE id = ?"));
    }

    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("changesThatEndAFlowOfAnAccount")
    void aFlowOfAnAccountThatNoLongerHoldsIsInvalid(String change) {
        long id = account("Local", FakeGoogle.email, true);
        var grant = sessions.create(id, SessionPersistence.REMEMBERED, null, null);
        var current = new SessionIdentity(id, 0, grant.account().familyId(), clock.instant(), true);
        String state = AuthenticationSecrets.generate();
        String proof = AuthenticationSecrets.generate();
        transactions.start(state, proof, current);
        jdbc.update(change, id);

        assertThatThrownBy(() -> transactions.consume(state, proof, current))
                .as("consume")
                .isInstanceOfSatisfying(
                        GoogleAuthenticationFailedException.class,
                        failure ->
                                assertThat(failure.reason())
                                        .as("reason")
                                        .isEqualTo(GoogleAuthenticationFailedException.Reason.INVALID_FLOW));
    }

    @Test
    void linkedCallbackCannotCrossAPasswordResetWhileWaitingForTheAccountLock() throws Exception {
        long id = account("Local", FakeGoogle.email, true);
        linkIdentity(id);
        var grant = sessions.create(id, SessionPersistence.REMEMBERED, null, null);
        var current = new SessionIdentity(id, 0, grant.account().familyId(), clock.instant(), true);
        String state = AuthenticationSecrets.generate();
        String proof = AuthenticationSecrets.generate();
        transactions.start(state, proof, current);
        var scope = transactions.consume(state, proof, current);
        assertThat(activeReconnections(id)).as("given: active reconnection").isEqualTo(1);
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        String callbackOutcome;
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var reset =
                    executor.submit(
                            () ->
                                    new TransactionTemplate(transactionManager)
                                            .executeWithoutResult(
                                                    status -> {
                                                        jdbc.queryForObject(
                                                                """
                                                                    SELECT id FROM auth_accounts
                                                                    WHERE id = ? FOR UPDATE
                                                                    """,
                                                                Long.class,
                                                                id);
                                                        locked.countDown();
                                                        try {
                                                            if (!release.await(8, TimeUnit.SECONDS))
                                                                throw new IllegalStateException(
                                                                        "Fixture verrou non libérée.");
                                                        } catch (InterruptedException error) {
                                                            Thread.currentThread().interrupt();
                                                            throw new IllegalStateException(
                                                                    "Fixture interrompue.");
                                                        }
                                                        jdbc.update(
                                                                """
                                                                    UPDATE auth_accounts
                                                                    SET credential_version = credential_version + 1
                                                                    WHERE id = ?
                                                                    """,
                                                                id);
                                                        jdbc.update(
                                                                """
                                                                    UPDATE auth_reconnections SET revoked = TRUE
                                                                    WHERE account_id = ?
                                                                    """,
                                                                id);
                                                    }));
            assertThat(locked.await(5, TimeUnit.SECONDS)).as("given: account lock held").isTrue();
            var callback =
                    executor.submit(
                            () ->
                                    outcome(
                                            "GRANTED",
                                            () ->
                                                    transactions.accept(
                                                            new GoogleIdentity(
                                                                    FakeGoogle.subject,
                                                                    FakeGoogle.email,
                                                                    true),
                                                            scope,
                                                            current,
                                                            state,
                                                            null,
                                                            current.familyId())));
            await().alias("callback waiting for the account lock")
                    .atMost(LOCK_ARRIVAL)
                    .pollInterval(Duration.ofMillis(10))
                    .until(this::callbackWaitsForAccountLock);

            release.countDown();
            reset.get(10, TimeUnit.SECONDS);
            callbackOutcome = callback.get(10, TimeUnit.SECONDS);
        } finally {
            release.countDown();
        }

        assertThat(callbackOutcome).as("callback outcome").isEqualTo("INVALID_FLOW");
        assertThat(activeReconnections(id)).as("active reconnections").isZero();
    }

    @Test
    void principalTransitionDuringOidcExchangeInvalidatesTheAnonymousFlow() throws Exception {
        long google = linkedGoogleOwner("GoogleOwner");
        account("OtherOwner", "other@example.invalid", true);
        var callbackBrowser = newBrowser();
        var flow = callbackBrowser.start();
        var otherTab = sharingCookiesOf(callbackBrowser);

        var held =
                whileExchangeIsInFlight(
                        callbackBrowser,
                        flow,
                        () ->
                                otherTab.post(
                                        "login",
                                        Map.of("identifier", "OtherOwner", "password", password)));

        assertThat(held.during().statusCode()).as("given: other tab logged in").isEqualTo(200);
        assertThat(held.callback().statusCode()).as("callback status").isEqualTo(302);
        assertThat(count("auth_google_pending")).as("pending flows").isZero();
        assertThat(activeReconnections(google)).as("reconnections of the Google account").isZero();
        assertThat(otherTab.username()).as("username of the other tab").isEqualTo("otherowner");
    }

    @Test
    void principalChangeAfterGoogleCallbackGrantRevokesThatGrant() {
        long id = linkedGoogleOwner("GoogleOwner");
        var accepted = acceptedCallback();
        var issued = accepted.callback().grant().account();
        assertThat(sessions.verify(id, issued.credentialVersion(), issued.familyId()).username())
                .as("given: the granted session is valid")
                .isEqualTo("googleowner");

        sessions.principalChanged(accepted.proof(), null);

        assertThatThrownBy(() -> sessions.verify(id, issued.credentialVersion(), issued.familyId()))
                .as("verification of the granted session")
                .isInstanceOf(AuthenticationFailedException.class);
    }

    @Test
    void principalChangeAfterGoogleCallbackGrantKeepsAnotherBrowserSession() {
        long id = linkedGoogleOwner("GoogleOwner");
        var otherBrowser = sessions.create(id, SessionPersistence.REMEMBERED, null, null);
        var accepted = acceptedCallback();

        sessions.principalChanged(accepted.proof(), null);

        assertThat(
                        sessions.verify(
                                        id,
                                        otherBrowser.account().credentialVersion(),
                                        otherBrowser.account().familyId())
                                .username())
                .as("username of the other browser")
                .isEqualTo("googleowner");
    }

    @Test
    void principalChangeAfterGoogleAccessGrantRevokesThatGrant() {
        long id = account("Local", FakeGoogle.email, true);
        var accepted = acceptedCallback();
        var access = grantedByAccess(accepted, id).account();
        assertThat(sessions.verify(id, access.credentialVersion(), access.familyId()).username())
                .as("given: the granted session is valid")
                .isEqualTo("local");

        sessions.principalChanged(accepted.proof(), null);

        assertThatThrownBy(() -> sessions.verify(id, access.credentialVersion(), access.familyId()))
                .as("verification of the granted session")
                .isInstanceOf(AuthenticationFailedException.class);
    }

    @Test
    void principalChangeAfterGoogleAccessGrantKeepsAnotherBrowserSession() {
        long id = account("Local", FakeGoogle.email, true);
        var otherBrowser = sessions.create(id, SessionPersistence.REMEMBERED, null, null);
        var accepted = acceptedCallback();
        grantedByAccess(accepted, id);

        sessions.principalChanged(accepted.proof(), null);

        assertThat(
                        sessions.verify(
                                        id,
                                        otherBrowser.account().credentialVersion(),
                                        otherBrowser.account().familyId())
                                .username())
                .as("username of the other browser")
                .isEqualTo("local");
    }

    private AuthenticationGrant grantedByAccess(Accepted accepted, long accountId) {
        var callback = accepted.callback();
        return transactions.access(
                callback.pendingId(),
                callback.pendingProof(),
                null,
                new Credentials(accountId, 0),
                null,
                null);
    }
}
