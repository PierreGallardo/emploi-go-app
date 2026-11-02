package fr.emploigo.auth.application.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import fr.emploigo.auth.application.failure.AuthenticationFailedException;
import fr.emploigo.auth.application.password.PasswordHashingBusyException;
import fr.emploigo.auth.application.quota.LoginAttempt;
import fr.emploigo.auth.application.quota.QuotaEnforcer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Duration;
import java.util.Optional;
import java.util.stream.Stream;

class PasswordAuthenticatorTest {
    private static final String PASSWORD = " mots de passe 🔑 ";
    private static final String ADDRESS = "127.0.0.1";

    private final PasswordChecker checker = mock(PasswordChecker.class);
    private final QuotaEnforcer quota = mock(QuotaEnforcer.class);
    private final LoginAttempt attempt = mock(LoginAttempt.class);
    private final PasswordAuthenticator passwords = new PasswordAuthenticator(checker, quota);

    /** A login that the checker accepts, for a test that reads only the identity. */
    private void authenticate(String identifier) {
        when(checker.check(identifier, PASSWORD)).thenReturn(Optional.of(new Credentials(7, 3)));
        passwords.authenticate(identifier, PASSWORD, ADDRESS);
    }

    @Test
    void successChecksAfterTheQuotaAndResetsTheFailuresOfTheIdentityOnce() {
        when(quota.beginLogin(anyString(), anyString())).thenReturn(attempt);
        when(checker.check("known", PASSWORD)).thenReturn(Optional.of(new Credentials(7, 3)));

        var credentials = passwords.authenticate("known", PASSWORD, ADDRESS);

        assertThat(credentials).as("credentials").isEqualTo(new Credentials(7, 3));
        var order = inOrder(quota, checker, attempt);
        order.verify(quota).beginLogin("known", ADDRESS);
        order.verify(checker).check("known", PASSWORD);
        order.verify(attempt).succeed();
    }

    @Test
    void refusedCheckFailsWithInvalidCredentialsAndStaysCounted() {
        when(quota.beginLogin(anyString(), anyString())).thenReturn(attempt);
        when(checker.check("known", PASSWORD)).thenReturn(Optional.empty());

        var failure =
                catchThrowableOfType(
                        AuthenticationFailedException.class,
                        () -> passwords.authenticate("known", PASSWORD, ADDRESS));

        assertThat(failure.reason())
                .as("reason")
                .isEqualTo(AuthenticationFailedException.Reason.INVALID_CREDENTIALS);
        verify(attempt, never()).succeed();
    }

    static Stream<Arguments> interruptions() {
        return Stream.of(
                argumentSet("plafond des calculs atteint", new PasswordHashingBusyException()),
                argumentSet(
                        "base indisponible",
                        new DataAccessResourceFailureException("base coupée")));
    }

    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("interruptions")
    void interruptedCheckReachesTheCallerAndStaysCounted(RuntimeException interruption) {
        when(quota.beginLogin(anyString(), anyString())).thenReturn(attempt);
        when(checker.check("known", PASSWORD)).thenThrow(interruption);

        assertThatThrownBy(() -> passwords.authenticate("known", PASSWORD, ADDRESS))
                .as("login")
                .isSameAs(interruption);

        verify(attempt, never()).succeed();
    }

    static Stream<Arguments> refusedQuotas() {
        return Stream.of(
                argumentSet(
                        "délai en cours",
                        AuthenticationFailedException.rateLimited(Duration.ofMinutes(1))),
                argumentSet(
                        "compteurs indisponibles", AuthenticationFailedException.unavailable()));
    }

    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("refusedQuotas")
    void refusedQuotaStopsBeforeTheAccountAndTheComputation(
            AuthenticationFailedException refusal) {
        doThrow(refusal).when(quota).beginLogin(anyString(), anyString());

        assertThatThrownBy(() -> passwords.authenticate("known", PASSWORD, ADDRESS))
                .as("login")
                .isSameAs(refusal);

        verifyNoInteractions(checker);
    }

    static Stream<Arguments> typedIdentifiers() {
        return Stream.of(
                argumentSet(
                        "email en majuscules", "JEUNE@Example.invalid", "jeune@example.invalid"),
                argumentSet(
                        "email entouré d'espaces",
                        "  jeune@example.invalid ",
                        "jeune@example.invalid"),
                argumentSet("pseudo en majuscules", "ECOLE_DEMO", "ecole_demo"),
                argumentSet("pseudo entouré d'espaces", "  ecole_demo ", "ecole_demo"));
    }

    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("typedIdentifiers")
    void identityIsTheNormalizedIdentifierAndNeverTheAccount(String typed, String expected) {
        when(quota.beginLogin(anyString(), anyString())).thenReturn(attempt);

        authenticate(typed);

        verify(quota).beginLogin(expected, ADDRESS);
        verify(checker).check(typed, PASSWORD);
    }

    @Test
    void emailAndUsernameOfTheSameAccountAreTwoIdentities() {
        when(quota.beginLogin(anyString(), anyString())).thenReturn(attempt);

        authenticate("known@example.invalid");
        authenticate("known");

        verify(quota).beginLogin("known@example.invalid", ADDRESS);
        verify(quota).beginLogin("known", ADDRESS);
    }

    static Stream<Arguments> unreadableIdentifiers() {
        return Stream.of(
                argumentSet("caractère de contrôle", "jeune\u0007"),
                argumentSet("pseudo trop long", "a".repeat(33)),
                argumentSet("pseudo avec tiret", "jeune-demo"),
                argumentSet("pseudo accentué", "léa"),
                argumentSet("a cyrillique", "c\u0430mille"),
                argumentSet("email sans domaine", "jeune@example"));
    }

    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("unreadableIdentifiers")
    void unreadableIdentifierHasACounterOfItsOwnAndIsStillChecked(String typed) {
        when(quota.beginLogin(anyString(), anyString())).thenReturn(attempt);

        authenticate(typed);

        verify(quota)
                .beginLogin(
                        argThat(
                                identity ->
                                        identity.startsWith("invalid:")
                                                && identity.length() == "invalid:".length() + 64),
                        eq(ADDRESS));
        verify(checker).check(typed, PASSWORD);
    }

    @Test
    void sameUnreadableIdentifierKeepsTheSameIdentityAndAnotherOneHasAnother() {
        when(quota.beginLogin(anyString(), anyString())).thenReturn(attempt);

        authenticate("jeune\u0007");
        authenticate("jeune\u0007");
        authenticate("autre\u0007");

        var identities = ArgumentCaptor.forClass(String.class);
        verify(quota, times(3)).beginLogin(identities.capture(), eq(ADDRESS));
        assertThat(identities.getAllValues().get(0))
                .as("first")
                .isEqualTo(identities.getAllValues().get(1));
        assertThat(identities.getAllValues().get(2))
                .as("other")
                .isNotEqualTo(identities.getAllValues().get(0));
    }
}
