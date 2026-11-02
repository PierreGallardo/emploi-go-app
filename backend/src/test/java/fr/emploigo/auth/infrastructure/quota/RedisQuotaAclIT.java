package fr.emploigo.auth.infrastructure.quota;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import fr.emploigo.auth.application.AuthenticationIntegrationTest;
import fr.emploigo.auth.application.quota.AuthenticationRateLimiter;
import fr.emploigo.auth.application.quota.IdentityLimits;
import fr.emploigo.auth.application.quota.LoginAttemptLimiter.Admitted;
import fr.emploigo.auth.application.quota.LoginAttemptLimiter.Limited;
import fr.emploigo.auth.application.quota.LoginAttemptLimiter;
import fr.emploigo.auth.application.quota.LoginLimits.FailureDelay;
import fr.emploigo.auth.application.quota.LoginLimits;
import fr.emploigo.auth.application.quota.QuotaLimits.MailLimits;
import fr.emploigo.auth.application.quota.QuotaLimits;
import fr.emploigo.auth.application.quota.QuotaScope;
import fr.emploigo.auth.application.registration.ConfirmationAttemptLimiter;
import fr.emploigo.auth.infrastructure.registration.RedisConfirmationAttemptLimiter;
import fr.emploigo.support.Containers;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * Every script of the quota runs with the production account: Redis checks the ACL of the commands
 * a script calls, so a command missing from {@link
 * RedisAuthenticationRateLimiter#REQUIRED_COMMANDS} fails with NOPERM.
 */
class RedisQuotaAclIT extends AuthenticationIntegrationTest {
    private static final String USER = "quota-it";
    private static final String PASSWORD = "quota-it-password";
    private static final String IDENTITY = "jeune@example.invalid";
    private static final String ADDRESS = "203.0.113.7";
    private static final LoginLimits LOGIN =
            new LoginLimits(
                    30,
                    Duration.ofMinutes(15),
                    new FailureDelay(
                            5,
                            Duration.ofSeconds(5),
                            2.0,
                            Duration.ofMinutes(5),
                            Duration.ofMinutes(30)));

    private LettuceConnectionFactory connections;
    private LoginAttemptLimiter login;
    private AuthenticationRateLimiter generic;
    private ConfirmationAttemptLimiter probes;

    @BeforeEach
    void accountOfTheApi() {
        var rules =
                Stream.concat(
                                Stream.of(
                                        "SETUSER",
                                        USER,
                                        "reset",
                                        "on",
                                        ">" + PASSWORD,
                                        "~auth:limits:*"),
                                Stream.of(
                                        RedisAuthenticationRateLimiter.REQUIRED_COMMANDS.split(
                                                " ")))
                        .map(String::getBytes)
                        .toArray(byte[][]::new);
        redis.execute((RedisCallback<Object>) connection -> connection.execute("ACL", rules));
        var configuration =
                new RedisStandaloneConfiguration(
                        Containers.redis().getHost(), Containers.redis().getMappedPort(6379));
        configuration.setUsername(USER);
        configuration.setPassword(PASSWORD);
        connections = new LettuceConnectionFactory(configuration);
        connections.afterPropertiesSet();
        var template = new StringRedisTemplate(connections);
        var meters = new SimpleMeterRegistry();
        probes = new RedisConfirmationAttemptLimiter(template, TestQuotaKeys.KEYS, clock);
        login = new RedisLoginAttemptLimiter(template, TestQuotaKeys.KEYS, clock, meters);
        generic = new RedisAuthenticationRateLimiter(template, TestQuotaKeys.KEYS, clock, meters);
    }

    @AfterEach
    void closeTheAccount() throws Exception {
        connections.destroy();
        Containers.redis()
                .execInContainer(
                        "redis-cli",
                        "-a",
                        Containers.REDIS_PASSWORD,
                        "--no-auth-warning",
                        "acl",
                        "deluser",
                        USER);
    }

    private void admitted(LoginAttemptLimiter.Admission admission) {
        assertThat(admission)
                .as("admission: the account of the API is not refused by the ACL")
                .isInstanceOf(Admitted.class);
    }

    private static final String PAGE_KEY = "k".repeat(43);
    private static final String OTHER_PAGE_KEY = "o".repeat(43);

    private List<ConfirmationAttemptLimiter.State> failuresOfThePage(int times) {
        return IntStream.range(0, times)
                .mapToObj(_ -> probes.fail(IDENTITY, PAGE_KEY).state())
                .toList();
    }

    @Test
    void confirmationProbesCountTheAttemptsOfAPageWithoutAnyRefusalOfTheAcl() {
        var states = failuresOfThePage(6);

        assertThat(states)
                .as("states: four wrong codes, then the attempts are spent")
                .containsExactly(
                        ConfirmationAttemptLimiter.State.WRONG,
                        ConfirmationAttemptLimiter.State.WRONG,
                        ConfirmationAttemptLimiter.State.WRONG,
                        ConfirmationAttemptLimiter.State.WRONG,
                        ConfirmationAttemptLimiter.State.SPENT,
                        ConfirmationAttemptLimiter.State.SPENT);
    }

    @Test
    void confirmationProbesStartTheHourOfWaitAtTheFifteenthFailureAndReportIt() {
        failuresOfThePage(5);
        probes.codeIssued(IDENTITY, PAGE_KEY);
        failuresOfThePage(5);
        probes.codeIssued(IDENTITY, PAGE_KEY);
        failuresOfThePage(4);

        var fifteenth = probes.fail(IDENTITY, PAGE_KEY);

        assertThat(fifteenth.state())
                .as("state")
                .isEqualTo(ConfirmationAttemptLimiter.State.WAITING);
        assertThat(fifteenth.delay()).as("wait").isEqualTo(Duration.ofHours(1));
        assertThat(probes.waiting(IDENTITY, PAGE_KEY))
                .as("wait read afterwards")
                .hasValueSatisfying(
                        wait ->
                                assertThat(wait)
                                        .isBetween(Duration.ofMinutes(59), Duration.ofHours(1)));
    }

    @Test
    void confirmationProbesStartAPageOverWhenANewCodeIsIssued() {
        failuresOfThePage(5);
        probes.codeIssued(IDENTITY, PAGE_KEY);

        assertThat(probes.fail(IDENTITY, PAGE_KEY).state())
                .as("state after a new code")
                .isEqualTo(ConfirmationAttemptLimiter.State.WRONG);
    }

    @Test
    void confirmationProbesOfTheHolderStartOverWhenAnotherPageIsIssuedACodeOfTheEmail() {
        probes.takeOver(IDENTITY, PAGE_KEY);
        probes.codeIssued(IDENTITY, PAGE_KEY);
        failuresOfThePage(5);
        probes.codeIssued(IDENTITY, OTHER_PAGE_KEY);

        var states = failuresOfThePage(5);

        assertThat(states)
                .as("states after a new code: four wrong codes, then the attempts are spent")
                .containsExactly(
                        ConfirmationAttemptLimiter.State.WRONG,
                        ConfirmationAttemptLimiter.State.WRONG,
                        ConfirmationAttemptLimiter.State.WRONG,
                        ConfirmationAttemptLimiter.State.WRONG,
                        ConfirmationAttemptLimiter.State.SPENT);
    }

    @Test
    void confirmationProbesAnswerExpiredOnceTheLastCodeIsOlderThanItsLife() {
        probes.codeIssued(IDENTITY, PAGE_KEY);
        clock.now = clock.now.plus(Duration.ofMinutes(16));

        assertThat(probes.fail(IDENTITY, PAGE_KEY).state())
                .as("state")
                .isEqualTo(ConfirmationAttemptLimiter.State.EXPIRED);
    }

    @Test
    void confirmationProbesOfAPageThatNeverAskedForACodeAreNotExpiredByAnotherPageCode() {
        probes.codeIssued(IDENTITY, OTHER_PAGE_KEY);
        clock.now = clock.now.plus(Duration.ofMinutes(16));

        assertThat(probes.fail(IDENTITY, PAGE_KEY).state())
                .as("state")
                .isEqualTo(ConfirmationAttemptLimiter.State.WRONG);
    }

    @Test
    void accountOfTheApiIsLimitedToTheDocumentedRule() {
        var refused = catchThrowable(() -> connections.getConnection().commands().flushDb());

        assertThat(refused).as("a command outside the rule").isNotNull();
    }

    @Test
    void loginBeginsUpToItsDelayWithoutAnyRefusalOfTheAcl() {
        redis.opsForValue()
                .set(
                        "auth:limits:login:failures:" + TestQuotaKeys.KEYS.identity(IDENTITY),
                        "4",
                        Duration.ofMinutes(30));

        admitted(login.begin(IDENTITY, ADDRESS, LOGIN));
        var refused = login.begin(IDENTITY, ADDRESS, LOGIN);

        assertThat(redis.hasKey("auth:limits:login:delay:" + TestQuotaKeys.KEYS.identity(IDENTITY)))
                .as("the attempt set the delay")
                .isTrue();
        assertThat(refused).as("the next attempt").isInstanceOf(Limited.class);
    }

    @Test
    void loginForgetIsRecordedWithoutAnyRefusalOfTheAcl() {
        admitted(login.begin(IDENTITY, ADDRESS, LOGIN));

        assertThat(login.forget(IDENTITY)).as("forget recorded").isTrue();
        assertThat(login.begin(IDENTITY, ADDRESS, LOGIN))
                .as("attempt after the forget")
                .isInstanceOf(Admitted.class);
    }

    @Test
    void emailActionRunsUpToItsGlobalThresholdWithoutAnyRefusalOfTheAcl() {
        var scope = QuotaScope.REGISTRATION_MAIL;
        var limits =
                new QuotaLimits(
                        100,
                        100,
                        Duration.ofMinutes(15),
                        new MailLimits(Duration.ofSeconds(60), 1, 1000));

        var outcomes =
                Stream.of("a", "b", "c")
                        .map(name -> generic.check(scope, name, ADDRESS, limits))
                        .toList();

        assertThat(outcomes)
                .as("outcomes: the threshold only alerts")
                .containsOnly(new AuthenticationRateLimiter.Allowed());
    }

    @Test
    void otherActionIsCountedWithoutAnyRefusalOfTheAcl() {
        var limits = new QuotaLimits(100, 1, Duration.ofMinutes(15), null);

        generic.check(QuotaScope.RECOVERY_CONFIRM, "identity", ADDRESS, limits);
        var second = generic.check(QuotaScope.RECOVERY_CONFIRM, "identity", ADDRESS, limits);

        assertThat(second)
                .as("second attempt of the identity")
                .isInstanceOf(AuthenticationRateLimiter.Limited.class);
    }

    @Test
    void identityAloneIsCountedWithoutAnyRefusalOfTheAcl() {
        var limits = new IdentityLimits(1, Duration.ofMinutes(15));

        generic.checkIdentity(QuotaScope.RESUME, "family", limits);
        var second = generic.checkIdentity(QuotaScope.RESUME, "family", limits);

        assertThat(second)
                .as("second attempt of the identity")
                .isInstanceOf(AuthenticationRateLimiter.Limited.class);
    }

    @Test
    void documentQuotesTheRuleThatTheAccountOfTheTestsHas() throws IOException {
        var document = Files.readString(Path.of("..", "docs", "exploitation.md"));

        assertThat(document)
                .as("docs/exploitation.md")
                .contains(RedisAuthenticationRateLimiter.REQUIRED_COMMANDS);
    }
}
