package fr.emploigo.auth.application.google;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import fr.emploigo.auth.application.quota.QuotaEnforcer;

import org.junit.jupiter.api.Test;

class StartGoogleLoginUseCaseTest {
    @Test
    void disabledGoogleStopsTheStartBeforeAnyWrite() {
        var client = mock(GoogleSignInClient.class);
        var transactions = mock(GoogleTransactions.class);
        var useCase =
                new StartGoogleLoginUseCase(
                        client,
                        transactions,
                        mock(QuotaEnforcer.class),
                        new GoogleSettings(false, "http://localhost:5173"));

        assertThatThrownBy(() -> useCase.start("proof", null, "127.0.0.1"))
                .as("start")
                .isInstanceOfSatisfying(
                        GoogleAuthenticationFailedException.class,
                        error ->
                                assertThat(error.reason())
                                        .as("reason")
                                        .isEqualTo(GoogleAuthenticationFailedException.Reason.UNAVAILABLE));

        verifyNoInteractions(client, transactions);
    }
}
