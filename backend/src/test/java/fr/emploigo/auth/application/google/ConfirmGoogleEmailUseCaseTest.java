package fr.emploigo.auth.application.google;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import fr.emploigo.auth.application.failure.AuthenticationFailedException;
import fr.emploigo.auth.application.quota.QuotaEnforcer;
import fr.emploigo.auth.application.quota.QuotaScope;
import fr.emploigo.auth.application.session.AuthenticationToken;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Duration;
import java.util.UUID;

class ConfirmGoogleEmailUseCaseTest {
    private static final UUID ID = UUID.fromString("0f8fad5b-d9cb-469f-a165-70867728950e");

    private final GoogleTransactions transactions = mock(GoogleTransactions.class);
    private final QuotaEnforcer quota = mock(QuotaEnforcer.class);

    private ConfirmGoogleEmailUseCase useCase(boolean enabled) {
        return new ConfirmGoogleEmailUseCase(
                transactions, quota, new GoogleSettings(enabled, "http://localhost:5173"));
    }

    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("fr.emploigo.auth.application.MalformedTokens#asLinksOfTheLengthOfAToken")
    void emailConfirmationOfAMalformedLinkIsRefusedAfterTheQuotaAndBeforeTheTransaction(
            String link) {
        assertThatThrownBy(() -> useCase(true).confirm(link, "127.0.0.1"))
                .as("confirm")
                .isInstanceOfSatisfying(
                        GoogleAuthenticationFailedException.class,
                        error ->
                                assertThat(error.reason())
                                        .as("reason")
                                        .isEqualTo(GoogleAuthenticationFailedException.Reason.INVALID_LINK));

        verify(quota).check(QuotaScope.GOOGLE_EMAIL_CONFIRM, link.substring(0, 36), "127.0.0.1");
        verifyNoInteractions(transactions);
    }

    @Test
    void disabledGoogleRegistrationIsReportedBeforeAMalformedLink() {
        assertThatThrownBy(() -> useCase(false).confirm("malformed", "127.0.0.1"))
                .as("confirm")
                .isInstanceOfSatisfying(
                        GoogleAuthenticationFailedException.class,
                        error ->
                                assertThat(error.reason())
                                        .as("reason")
                                        .isEqualTo(GoogleAuthenticationFailedException.Reason.UNAVAILABLE));

        verifyNoInteractions(quota, transactions);
    }

    @Test
    void refusedQuotaIsReportedBeforeAMalformedLink() {
        doThrow(AuthenticationFailedException.rateLimited(Duration.ofMinutes(1)))
                .when(quota)
                .check(any(), anyString(), anyString());

        assertThatThrownBy(() -> useCase(true).confirm("malformed", "127.0.0.1"))
                .as("confirm")
                .isInstanceOf(AuthenticationFailedException.class);

        verifyNoInteractions(transactions);
    }

    @Test
    void emailConfirmationOfAWellFormedLinkReachesTheTransactionWithTheTokenItReads() {
        String secret = "s".repeat(43);

        useCase(true).confirm(ID + "." + secret, "127.0.0.1");

        verify(transactions).confirmEmail(new AuthenticationToken(ID, secret));
    }
}
