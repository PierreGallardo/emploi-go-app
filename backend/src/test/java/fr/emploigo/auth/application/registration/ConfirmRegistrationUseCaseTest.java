package fr.emploigo.auth.application.registration;

import static fr.emploigo.auth.application.session.SessionPersistence.REMEMBERED;
import static fr.emploigo.auth.application.session.SessionPersistence.UNTIL_BROWSER_CLOSES;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import fr.emploigo.auth.application.failure.AuthenticationFailedException;
import fr.emploigo.auth.application.quota.QuotaEnforcer;
import fr.emploigo.auth.application.quota.QuotaScope;
import fr.emploigo.auth.application.session.AuthenticatedAccount;
import fr.emploigo.auth.application.session.AuthenticationGrant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.UUID;
import java.util.stream.Stream;

class ConfirmRegistrationUseCaseTest {
    private static final String KEY = "k".repeat(43);
    private static final String EMAIL = "jeune@example.invalid";

    private final RegistrationTransactions transactions = mock(RegistrationTransactions.class);
    private final QuotaEnforcer quota = mock(QuotaEnforcer.class);
    private final FakeConfirmationAttemptLimiter limiter = new FakeConfirmationAttemptLimiter();
    private final ConfirmRegistrationUseCase registration =
            new ConfirmRegistrationUseCase(transactions, quota, limiter);

    private AuthenticationGrant confirm(String typedCode) {
        return registration.confirm(
                EMAIL, typedCode, KEY, UNTIL_BROWSER_CLOSES, null, null, "127.0.0.1");
    }

    private static RegistrationFailedException.Reason reasonOf(Throwable error) {
        return ((RegistrationFailedException) error).reason();
    }

    static Stream<Arguments> answersOfTheProbes() {
        return Stream.of(
                argumentSet(
                        "mauvais code",
                        ConfirmationAttemptLimiter.State.WRONG,
                        RegistrationFailedException.Reason.INVALID_CODE),
                argumentSet(
                        "essais épuisés",
                        ConfirmationAttemptLimiter.State.SPENT,
                        RegistrationFailedException.Reason.CODE_ATTEMPTS_EXCEEDED),
                argumentSet(
                        "code expiré",
                        ConfirmationAttemptLimiter.State.EXPIRED,
                        RegistrationFailedException.Reason.CODE_EXPIRED),
                argumentSet(
                        "compteurs indisponibles",
                        ConfirmationAttemptLimiter.State.UNAVAILABLE,
                        RegistrationFailedException.Reason.INVALID_CODE));
    }

    @ParameterizedTest(name = "{0}")
    @NullSource
    @ValueSource(
            strings = {"", "12345", "1234567", "abcdef", "12 34-5x", "123456789", "１２３４５６"})
    void confirmationOfAMalformedCodeIsRefusedBeforeTheTransaction(String typed) {
        assertThatThrownBy(() -> confirm(typed))
                .as("confirm")
                .isInstanceOf(RegistrationFailedException.class)
                .extracting(ConfirmRegistrationUseCaseTest::reasonOf)
                .isEqualTo(RegistrationFailedException.Reason.INVALID_CODE);

        verify(quota).check(QuotaScope.CONFIRMATION, EMAIL + "\0" + KEY, "127.0.0.1");
        verifyNoInteractions(transactions);
    }

    @Test
    void refusedQuotaIsReportedBeforeAMalformedCode() {
        doThrow(AuthenticationFailedException.rateLimited(Duration.ofMinutes(1)))
                .when(quota)
                .check(eq(QuotaScope.CONFIRMATION), anyString(), anyString());

        assertThatThrownBy(() -> confirm("malformed"))
                .as("confirm")
                .isInstanceOf(AuthenticationFailedException.class);

        verifyNoInteractions(transactions);
    }

    @Test
    void malformedCodeIsRefusedAsInvalidCode() {
        assertThatThrownBy(() -> confirm("malformed"))
                .as("confirm")
                .isInstanceOf(RegistrationFailedException.class)
                .extracting(ConfirmRegistrationUseCaseTest::reasonOf)
                .isEqualTo(RegistrationFailedException.Reason.INVALID_CODE);
    }

    @Test
    void confirmationReachesTheTransactionWithTheNormalizedCodeTheKeyAndTheChoiceOfThePage() {
        var family = UUID.randomUUID();
        var grant =
                new AuthenticationGrant(
                        new AuthenticatedAccount(1, null, "Jeune", 0, UUID.randomUUID()), "secret");
        when(transactions.confirm(any(), any(), any(), any(), any(), any()))
                .thenReturn(ConfirmRegistrationResult.granted(grant));

        var result =
                registration.confirm(
                        EMAIL, "123 456", KEY, REMEMBERED, "cookie", family, "127.0.0.1");

        assertThat(result).as("grant").isSameAs(grant);
        verify(transactions).confirm(EMAIL, "123456", KEY, REMEMBERED, "cookie", family);
    }

    @Test
    void refusedConfirmationIsThrownWithItsReason() {
        when(transactions.confirm(any(), any(), any(), any(), any(), any()))
                .thenReturn(
                        ConfirmRegistrationResult.refused(
                                RegistrationFailedException.Reason.CODE_EXPIRED));

        assertThatThrownBy(() -> confirm("123456"))
                .as("confirm")
                .isInstanceOf(RegistrationFailedException.class)
                .extracting(ConfirmRegistrationUseCaseTest::reasonOf)
                .isEqualTo(RegistrationFailedException.Reason.CODE_EXPIRED);
    }

    @Test
    void blockedConfirmationIsThrownAsBlockedCodesWithTheWait() {
        when(transactions.confirm(any(), any(), any(), any(), any(), any()))
                .thenReturn(ConfirmRegistrationResult.blocked(Duration.ofMinutes(42)));

        assertThatThrownBy(() -> confirm("123456"))
                .as("confirm")
                .isInstanceOfSatisfying(
                        AuthenticationFailedException.class,
                        failure -> {
                            assertThat(failure.reason())
                                    .as("reason")
                                    .isEqualTo(AuthenticationFailedException.Reason.CODES_BLOCKED);
                            assertThat(failure.retryAfter())
                                    .as("wait")
                                    .isEqualTo(Duration.ofMinutes(42));
                        });
    }

    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("answersOfTheProbes")
    void foreignPageIsAnsweredWithTheRefusalOfTheProbes(
            ConfirmationAttemptLimiter.State state, RegistrationFailedException.Reason expected) {
        when(transactions.confirm(any(), any(), any(), any(), any(), any()))
                .thenReturn(ConfirmRegistrationResult.ofForeignPage());
        limiter.answer(state, Duration.ZERO);

        assertThatThrownBy(() -> confirm("123456"))
                .as("confirm")
                .isInstanceOf(RegistrationFailedException.class)
                .extracting(ConfirmRegistrationUseCaseTest::reasonOf)
                .isEqualTo(expected);
    }

    @Test
    void foreignPageWaitingForTheHourIsThrownAsBlockedCodesWithTheWait() {
        when(transactions.confirm(any(), any(), any(), any(), any(), any()))
                .thenReturn(ConfirmRegistrationResult.ofForeignPage());
        limiter.answer(ConfirmationAttemptLimiter.State.WAITING, Duration.ofMinutes(42));

        assertThatThrownBy(() -> confirm("123456"))
                .as("confirm")
                .isInstanceOfSatisfying(
                        AuthenticationFailedException.class,
                        failure -> {
                            assertThat(failure.reason())
                                    .as("reason")
                                    .isEqualTo(AuthenticationFailedException.Reason.CODES_BLOCKED);
                            assertThat(failure.retryAfter())
                                    .as("wait")
                                    .isEqualTo(Duration.ofMinutes(42));
                        });
    }

    @Test
    void pageOfARegistrationDoesNotUseTheProbes() {
        when(transactions.confirm(any(), any(), any(), any(), any(), any()))
                .thenReturn(
                        ConfirmRegistrationResult.refused(
                                RegistrationFailedException.Reason.INVALID_CODE));

        assertThatThrownBy(() -> confirm("123456"))
                .as("confirm")
                .isInstanceOf(RegistrationFailedException.class);

        assertThat(limiter.failures()).as("failed attempts counted apart").isEmpty();
    }
}
