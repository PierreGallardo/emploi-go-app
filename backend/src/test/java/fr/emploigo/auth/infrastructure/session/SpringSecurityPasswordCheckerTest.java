package fr.emploigo.auth.infrastructure.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;
import static org.mockito.AdditionalMatchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import fr.emploigo.auth.application.account.AccountRepository;
import fr.emploigo.auth.application.account.FakeAccountRepository;
import fr.emploigo.auth.application.password.FakePasswordEncoder;
import fr.emploigo.auth.application.password.HashingGate;
import fr.emploigo.auth.application.password.PasswordHasher;
import fr.emploigo.auth.application.password.PasswordHashingBusyException;
import fr.emploigo.auth.application.session.Credentials;
import fr.emploigo.auth.domain.Account;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.UUID;
import java.util.stream.Stream;

class SpringSecurityPasswordCheckerTest {
    private static final String PASSWORD = " mots de passe 🔑 ";
    private static final String HASH = new FakePasswordEncoder().encode(PASSWORD);

    private final PasswordEncoder encoder = spy(new FakePasswordEncoder());
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final PasswordHasher hashing =
            new PasswordHasher(encoder, meters, new MockEnvironment());
    private final FakeAccountRepository accounts = new FakeAccountRepository();
    private final SpringSecurityPasswordChecker checker =
            new SpringSecurityPasswordChecker(accounts, hashing);

    private static Account account(
            long id,
            String username,
            String passwordHash,
            boolean verified,
            boolean enabled,
            String role) {
        return new Account(
                id,
                UUID.randomUUID(),
                username,
                username + "@example.invalid",
                passwordHash,
                verified,
                enabled,
                role,
                4);
    }

    private static Account young(String username) {
        return account(9L, username, HASH, true, true, "JEUNE");
    }

    private double rejections(String operation) {
        return meters.counter("auth.password.hashing.rejections", "operation", operation).count();
    }

    static Stream<Arguments> typedIdentifiers() {
        return Stream.of(
                argumentSet("pseudo", "known"),
                argumentSet("pseudo en majuscules entouré d'espaces", "  KNOWN "),
                argumentSet("email", "known@example.invalid"),
                argumentSet("email en majuscules", "Known@Example.invalid"));
    }

    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("typedIdentifiers")
    void rightPasswordGivesTheAccountAndItsCredentialVersion(String identifier) {
        accounts.store(young("known"));

        var credentials = checker.check(identifier, PASSWORD);

        assertThat(credentials).as("credentials").contains(new Credentials(9L, 4));
    }

    @Test
    void wrongPasswordIsRefused() {
        accounts.store(young("known"));

        var credentials = checker.check("known", "un autre mot de passe");

        assertThat(credentials).as("credentials").isEmpty();
        verify(encoder).matches("un autre mot de passe", HASH);
    }

    static Stream<Arguments> accountsThatCannotLogIn() {
        return Stream.of(
                argumentSet("compte inconnu", "missing"),
                argumentSet("identifiant illisible", "jeune-demo"),
                argumentSet("compte non vérifié", "pending"),
                argumentSet("compte désactivé", "disabled"),
                argumentSet("compte sans mot de passe", "google"),
                argumentSet("compte administrateur", "admin"));
    }

    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("accountsThatCannotLogIn")
    void accountThatCannotLogInIsRefusedLikeAnUnknownOneAfterADummyComputation(String identifier) {
        accounts.store(account(1L, "pending", HASH, false, true, "JEUNE"));
        accounts.store(account(2L, "disabled", HASH, true, false, "JEUNE"));
        accounts.store(account(3L, "google", null, true, true, "JEUNE"));
        accounts.store(account(4L, "admin", HASH, true, true, "ADMIN"));

        var credentials = checker.check(identifier, PASSWORD);

        assertThat(credentials).as("credentials").isEmpty();
        verify(encoder).matches(eq(PASSWORD), not(eq(HASH)));
    }

    @Test
    void unreadableIdentifierIsNeverLookedUp() {
        var repository = mock(AccountRepository.class);

        var isolated = new SpringSecurityPasswordChecker(repository, hashing);

        var credentials = isolated.check("jeune-demo", PASSWORD);

        assertThat(credentials).as("credentials").isEmpty();
        verifyNoInteractions(repository);
    }

    static Stream<Arguments> identifiersAtTheLimit() {
        return Stream.of(
                argumentSet("compte connu", "known"), argumentSet("compte inconnu", "missing"));
    }

    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("identifiersAtTheLimit")
    void loginAtTheLimitIsRefusedWithoutComputingAndCounted(String identifier) {
        accounts.store(young("known"));
        // Spring Security computes its dummy hash on the first login: done here, before the limit.
        checker.check("missing", PASSWORD);
        clearInvocations(encoder);
        try (var gate = new HashingGate(hashing, encoder)) {
            gate.saturate(hashing.limit());
            double before = rejections("matches");

            assertThatThrownBy(() -> checker.check(identifier, PASSWORD))
                    .as("login")
                    .isInstanceOf(PasswordHashingBusyException.class);

            assertThat(rejections("matches") - before).as("refusals counted").isEqualTo(1);
            verify(encoder, never()).matches(eq(PASSWORD), anyString());
        }
    }

    @Test
    void firstLoginAtTheLimitIsRefusedBeforeTheDummyHashAndCounted() {
        try (var gate = new HashingGate(hashing, encoder)) {
            gate.saturate(hashing.limit());

            assertThatThrownBy(() -> checker.check("missing", PASSWORD))
                    .as("login")
                    .isInstanceOf(PasswordHashingBusyException.class);

            assertThat(rejections("encode")).as("refusals counted").isEqualTo(1);
            verify(encoder, never()).matches(eq(PASSWORD), anyString());
        }
    }

    @Test
    void loginAfterARefusedDummyHashComputesItOnceTheLimitIsFree() {
        accounts.store(young("known"));
        try (var gate = new HashingGate(hashing, encoder)) {
            gate.saturate(hashing.limit());
            assertThatThrownBy(() -> checker.check("missing", PASSWORD))
                    .as("given: the dummy hash is refused")
                    .isInstanceOf(PasswordHashingBusyException.class);
            gate.releaseAndWait();
        }

        var credentials = checker.check("known", PASSWORD);

        assertThat(credentials).as("credentials").contains(new Credentials(9L, 4));
    }

    static Stream<Arguments> unavailableReads() {
        return Stream.of(
                argumentSet("pseudo", "known"), argumentSet("email", "known@example.invalid"));
    }

    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("unavailableReads")
    void databaseOutageReachesTheCallerAsItIsWithoutComputing(String identifier) {
        var outage = new DataAccessResourceFailureException("base coupée");
        var repository = mock(AccountRepository.class);
        when(repository.findByUsername("known")).thenThrow(outage);
        when(repository.findByEmailKey("known@example.invalid")).thenThrow(outage);

        assertThatThrownBy(
                        () ->
                                new SpringSecurityPasswordChecker(repository, hashing)
                                        .check(identifier, PASSWORD))
                .as("login")
                .isSameAs(outage);

        verify(encoder, never()).matches(any(), any());
    }
}
