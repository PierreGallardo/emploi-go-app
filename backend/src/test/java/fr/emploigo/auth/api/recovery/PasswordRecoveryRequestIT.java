package fr.emploigo.auth.api.recovery;

import static fr.emploigo.support.FakeProviders.MAIL_PAYLOADS;
import static fr.emploigo.support.FakeProviders.MAIL_RESPONSE;
import static fr.emploigo.support.FakeProviders.mailStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import fr.emploigo.support.MailCounters;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

class PasswordRecoveryRequestIT extends PasswordRecoveryIntegrationTest {
    private static final String GOOGLE_EMAIL = "google@example.invalid";
    private static final String PENDING_EMAIL = "pending@example.invalid";

    private List<String> passwordHashes() {
        return jdbc.queryForList(
                "SELECT COALESCE(password_hash, '<aucun>') FROM auth_accounts ORDER BY id",
                String.class);
    }

    @Test
    void recoveryRequestLeavesPasswordAndExistingSessionUntouched() throws Exception {
        long id = owner();
        var browser = loggedIn();

        var response = forgot(newBrowser(), EMAIL);

        assertThat(response.statusCode()).as("status").isEqualTo(202);
        assertThat(browser.get("session").statusCode())
                .as("existing session status")
                .isEqualTo(200);
        assertThat(credentialVersion(id)).as("credential version").isZero();
        assertThat(passwordMatches(id, original)).as("password is still the original one").isTrue();
    }

    static Stream<Arguments> recoveryAddresses() {
        return Stream.of(
                argumentSet("compte local vérifié", EMAIL, 1L),
                argumentSet("adresse absente", ABSENT_EMAIL, 0L),
                argumentSet("compte Google sans mot de passe", GOOGLE_EMAIL, 0L),
                argumentSet("compte local non vérifié", PENDING_EMAIL, 0L));
    }

    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("recoveryAddresses")
    void recoveryRequestAnswers202WithEmptyBodyAndTheSameWaitAndOnlyAVerifiedLocalAccountGetsALink(
            String address, long links) throws Exception {
        owner();
        account(GOOGLE_EMAIL, true, false);
        account(PENDING_EMAIL, false, true);
        var hashes = passwordHashes();

        var response = forgot(newBrowser(), address);

        assertThat(response.statusCode()).as("status").isEqualTo(202);
        assertThat(response.body()).as("body").isEmpty();
        assertThat(response.headers().firstValue("Retry-After"))
                .as("wait, the same with or without an account")
                .hasValue("60");
        assertThat(count("auth_password_resets")).as("recovery links created").isEqualTo(links);
        assertThat(passwordHashes()).as("password hashes").isEqualTo(hashes);
    }

    @Test
    void googleOnlyAccountReceivesTheProviderRecoveryNotice() throws Exception {
        account(GOOGLE_EMAIL, true, false);

        var response = forgot(newBrowser(), GOOGLE_EMAIL);

        assertThat(response.statusCode()).as("status").isEqualTo(202);
        assertThat(MAIL_PAYLOADS.values())
                .as("messages sent")
                .anyMatch(value -> value.contains("accounts.google.com/signin/recovery"));
    }

    @Test
    void mailBodyReachesTheProviderWithTheFieldsInTheFixedOrder() throws Exception {
        account(GOOGLE_EMAIL, true, false);

        var response = forgot(newBrowser(), GOOGLE_EMAIL);

        assertThat(response.statusCode()).as("status").isEqualTo(202);
        assertThat(MAIL_PAYLOADS.values())
                .as("messages sent")
                .singleElement()
                .asString()
                .matches(
                        "\\{\"from\":\"[^\"]+\",\"to\":\\[\""
                                + java.util.regex.Pattern.quote(GOOGLE_EMAIL)
                                + "\"\\],\"subject\":\"[^\"]+\",\"text\":\".+\"\\}");
    }

    static Stream<Arguments> failingProviderAddresses() {
        return Stream.of(
                argumentSet("compte existant", EMAIL),
                argumentSet("adresse absente", ABSENT_EMAIL));
    }

    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("failingProviderAddresses")
    void providerRefusalAnswers503WithoutLeakingProviderDetailNorAddress(String address)
            throws Exception {
        owner();
        mailStatus = 403;

        var response = forgot(newBrowser(), address);

        assertThat(response.statusCode()).as("status").isEqualTo(503);
        assertThat(response.body()).as("body").doesNotContain("private-provider-detail", address);
    }

    @Test
    void providerRefusalAnswersTheSameBodyForExistingAndAbsentAddresses() throws Exception {
        owner();
        mailStatus = 403;
        var browser = newBrowser();

        var existing = forgot(browser, EMAIL);
        var absent = forgot(browser, ABSENT_EMAIL);

        assertThat(existing.statusCode()).as("given: existing-address status").isEqualTo(503);
        assertThat(absent.statusCode()).as("given: absent-address status").isEqualTo(503);
        assertThat(absent.body()).as("body for the absent address").isEqualTo(existing.body());
    }

    @Test
    void requestsForExistingAndAbsentAddressesBothWaitForTheProviderBeforeAnswering202()
            throws Exception {
        owner();

        try (var pool = Executors.newVirtualThreadPerTaskExecutor();
                var hold = MAIL_RESPONSE.hold()) {
            var existing = pool.submit(() -> forgot(newBrowser(), EMAIL).statusCode());
            var absent = pool.submit(() -> forgot(newBrowser(), ABSENT_EMAIL).statusCode());
            hold.awaitRequests(2);
            await().alias("both requests still waiting for the provider")
                    .during(Duration.ofMillis(300))
                    .atMost(Duration.ofSeconds(2))
                    .until(() -> !existing.isDone() && !absent.isDone());
            hold.release();

            assertThat(existing.get()).as("existing-address status").isEqualTo(202);
            assertThat(absent.get()).as("absent-address status").isEqualTo(202);
        }
    }

    @Test
    void secondRequestWithinTheCooldownAnswers429WithItsRemainingWait() throws Exception {
        owner();
        var browser = newBrowser();
        forgot(browser, EMAIL);

        var second = forgot(browser, EMAIL);

        assertThat(second.statusCode()).as("status").isEqualTo(429);
        assertThat(code(second)).as("code").isEqualTo("MAIL_RECENTLY_SENT");
        assertThat(second.headers().firstValue("Retry-After").map(Integer::parseInt))
                .as("Retry-After, in seconds")
                .hasValueSatisfying(wait -> assertThat(wait).isBetween(1, 60));
    }

    @Test
    void secondRequestWithinTheCooldownSendsNoEmailAndChangesNothing() throws Exception {
        long id = owner();
        var browser = newBrowser();
        String link = requestedLink(EMAIL);
        var mail = new MailCounters();

        forgot(browser, EMAIL);

        assertThat(mail.calls()).as("provider calls").isZero();
        assertThat(link(EMAIL)).as("pending link").isEqualTo(link);
        assertThat(credentialVersion(id)).as("credential version").isZero();
        assertThat(passwordMatches(id, original)).as("password unchanged").isTrue();
    }

    @Test
    void requestOnceTheCooldownHasPassedAnswers202WithANewEmail() throws Exception {
        owner();
        var browser = newBrowser();
        String first = requestedLink(EMAIL);
        permitNextMail(EMAIL);
        var mail = new MailCounters();

        var response = forgot(browser, EMAIL);

        assertThat(response.statusCode()).as("status").isEqualTo(202);
        assertThat(mail.sends()).as("messages sent").isEqualTo(1);
        assertThat(link(EMAIL)).as("pending link").isNotEqualTo(first);
    }

    @Test
    void recoveryRequestFromAnOpenClientThatStillSendsARequestIdIsAccepted() throws Exception {
        owner();
        var body = Map.of("email", EMAIL, "requestId", UUID.randomUUID());

        var response = newBrowser().post("password/forgot", body);

        assertThat(response.statusCode()).as("status").isEqualTo(202);
        assertThat(count("auth_password_resets")).as("recovery links created").isEqualTo(1);
    }

    @Test
    void countersLostAtRedisDoNotBlockTheNextRequest() throws Exception {
        var browser = newBrowser();
        forgot(browser, EMAIL);
        assertThat(forgot(browser, EMAIL).statusCode())
                .as("given: cooldown reached")
                .isEqualTo(429);
        redis.delete(redis.keys("auth:limits:*"));

        var response = forgot(browser, EMAIL);

        assertThat(response.statusCode()).as("status").isEqualTo(202);
    }
}
