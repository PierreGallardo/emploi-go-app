package fr.emploigo.auth.api.recovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;

import fr.emploigo.auth.domain.TestPasswords;
import fr.emploigo.auth.infrastructure.quota.TestQuotaKeys;

import io.micrometer.core.instrument.MeterRegistry;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.Map;
import java.util.stream.Collectors;

class PasswordRecoveryLoginDelayIT extends PasswordRecoveryIntegrationTest {
    private static final String USERNAME = "Jeune_fixture";
    private static final String STORED_USERNAME = "jeune_fixture";

    @Autowired MeterRegistry meters;

    /**
     * An account with a username of its own, so that its email and its username are two identities
     * of the login.
     */
    private long ownerWithAUsername() {
        return jdbc.queryForObject(
                """
                INSERT INTO auth_accounts(
                    username, email_key, password_hash, email_verified, enabled)
                VALUES (?, ?, ?, TRUE, TRUE) RETURNING id
                """,
                Long.class,
                STORED_USERNAME,
                EMAIL,
                passwords.encode(original));
    }

    private static String failuresKey(String identity) {
        return "auth:limits:login:failures:" + TestQuotaKeys.KEYS.identity(identity);
    }

    /**
     * Puts an identity of the login in a five-minute delay: ten failures are counted, then a wrong
     * password sets it.
     */
    private void heldInADelay(String identifier, String identity, String password)
            throws Exception {
        redis.opsForValue().set(failuresKey(identity), "10", Duration.ofMinutes(30));
        assertThat(login(newBrowser(), identifier, "not-" + password).statusCode())
                .as("given: failure counted")
                .isEqualTo(401);
        var refused = login(newBrowser(), identifier, password);
        assertThat(refused.statusCode()).as("given: " + identifier + " in a delay").isEqualTo(429);
        assertThat(refused.headers().firstValue("Retry-After").map(Integer::parseInt))
                .as("given: Retry-After, in seconds")
                .hasValueSatisfying(wait -> assertThat(wait).isBetween(60, 300));
    }

    /** Counters of the login address: a recovery must leave them untouched. */
    private Map<String, String> addressCounters() {
        return redis.keys("auth:limits:ip:*").stream()
                .collect(Collectors.toMap(key -> key, key -> redis.opsForValue().get(key)));
    }

    private Map<String, String> loginCounters() {
        return redis.keys("auth:limits:login:*").stream()
                .collect(Collectors.toMap(key -> key, key -> redis.opsForValue().get(key)));
    }

    private double unrecordedResets() {
        return meters.get("auth.rate_limit.login.unrecorded")
                .tag("outcome", "reset")
                .counter()
                .count();
    }

    @Test
    void accountHeldInADelayLogsInAtOnceByEmailAndByUsernameOnceItsPasswordIsRecovered()
            throws Exception {
        ownerWithAUsername();
        heldInADelay(EMAIL, EMAIL, original);
        heldInADelay(USERNAME, STORED_USERNAME, original);

        resetDone(requestedLink(EMAIL));

        assertThat(login(newBrowser(), EMAIL, replacement).statusCode())
                .as("login by email")
                .isEqualTo(200);
        assertThat(login(newBrowser(), USERNAME, replacement).statusCode())
                .as("login by username")
                .isEqualTo(200);
    }

    @Test
    void recoveryForgetsTheFailuresAndDelayOfBothIdentitiesButNotTheAddressCounters()
            throws Exception {
        ownerWithAUsername();
        heldInADelay(EMAIL, EMAIL, original);
        heldInADelay(USERNAME, STORED_USERNAME, original);
        var addressBefore = addressCounters();
        assertThat(addressBefore).as("given: the address counted the attempts").hasSize(1);

        resetDone(requestedLink(EMAIL));

        assertThat(redis.keys("auth:limits:login:failures:*"))
                .as("failures of the identities")
                .isEmpty();
        assertThat(redis.keys("auth:limits:login:delay:*"))
                .as("delays of the identities")
                .isEmpty();
        assertThat(addressCounters()).as("counters of the address").isEqualTo(addressBefore);
    }

    @Test
    void recoveryDoesNotForgetTheDelayOfAnotherAccount() throws Exception {
        ownerWithAUsername();
        account(ABSENT_EMAIL, true, true);
        heldInADelay(ABSENT_EMAIL, ABSENT_EMAIL, original);

        resetDone(requestedLink(EMAIL));

        assertThat(login(newBrowser(), ABSENT_EMAIL, original).statusCode())
                .as("other account in its delay")
                .isEqualTo(429);
    }

    @Test
    void recoveryWhoseResetIsLostStillChangesThePasswordAndTheDelayStays() throws Exception {
        ownerWithAUsername();
        heldInADelay(EMAIL, EMAIL, original);
        doReturn(false).when(loginLimiter).forget(anyString());
        double before = unrecordedResets();
        String link = requestedLink(EMAIL);

        var response = resetPassword(newBrowser(), link);

        assertThat(response.statusCode()).as("status").isEqualTo(200);
        assertThat(unrecordedResets() - before)
                .as("resets not recorded: the email and the username")
                .isEqualTo(2);
        assertThat(login(newBrowser(), EMAIL, replacement).statusCode())
                .as("login in the delay")
                .isEqualTo(429);
    }

    @Test
    void secondResetOnAUsedLinkAnswers410AndLeavesTheLoginDelayInPlace() throws Exception {
        ownerWithAUsername();
        String link = requestedLink(EMAIL);
        resetDone(link);
        heldInADelay(EMAIL, EMAIL, replacement);

        var second = resetPassword(newBrowser(), link);

        assertThat(second.statusCode()).as("status").isEqualTo(410);
        assertThat(code(second)).as("code").isEqualTo("USED_LINK");
        assertThat(login(newBrowser(), EMAIL, replacement).statusCode())
                .as("login in the delay")
                .isEqualTo(429);
    }

    @Test
    void secondResetOnAUsedLinkChangesNeitherThePasswordNorTheLoginCounters() throws Exception {
        long id = ownerWithAUsername();
        String link = requestedLink(EMAIL);
        resetDone(link);
        heldInADelay(EMAIL, EMAIL, replacement);
        var before = loginCounters();

        var second = newBrowser().post("password/reset", change(link, TestPasswords.random()));

        assertThat(second.statusCode()).as("status").isEqualTo(410);
        assertThat(passwordMatches(id, replacement)).as("password of the first reset").isTrue();
        assertThat(credentialVersion(id)).as("credential version").isEqualTo(1);
        assertThat(loginCounters()).as("counters of the login").isEqualTo(before);
    }
}
