package fr.emploigo.auth.application.google;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import fr.emploigo.auth.application.quota.QuotaEnforcer;
import fr.emploigo.auth.application.quota.QuotaScope;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

class HandleGoogleCallbackUseCaseTest {
    private final GoogleTransactions transactions = mock(GoogleTransactions.class);
    private final QuotaEnforcer quota = mock(QuotaEnforcer.class);
    private final FakeGoogleSignInClient client = new FakeGoogleSignInClient();

    private HandleGoogleCallbackUseCase useCase(boolean enabled) {
        return new HandleGoogleCallbackUseCase(
                client, transactions, quota, new GoogleSettings(enabled, "http://localhost:5173"));
    }

    private static OAuth2AuthorizationRequest original() {
        return OAuth2AuthorizationRequest.authorizationCode()
                .authorizationUri("https://accounts.example.invalid/authorize")
                .clientId("client")
                .state("state")
                .build();
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
        "INVALID_FLOW, INVALID_FLOW",
        "CANCELLED, CANCELLED",
        "PROVIDER_REJECTED, PROVIDER_REJECTED",
        "UNAVAILABLE, UNAVAILABLE"
    })
    void refusalOfTheProviderKeepsItsReasonAndAcceptsNoIdentity(
            GoogleSignInClient.Refusal refusal, GoogleAuthenticationFailedException.Reason reason) {
        client.answer(new GoogleSignInClient.Verification.Refused(refusal));

        assertThatThrownBy(
                        () ->
                                useCase(true)
                                        .handle(
                                                original(),
                                                "proof",
                                                null,
                                                "code",
                                                "state",
                                                null,
                                                null,
                                                null,
                                                "127.0.0.1"))
                .as("callback")
                .isInstanceOfSatisfying(
                        GoogleAuthenticationFailedException.class,
                        error -> assertThat(error.reason()).as("reason").isEqualTo(reason));

        verify(transactions, never()).accept(any(), any(), any(), any(), any(), any());
    }

    @Test
    void disabledGoogleStopsTheCallbackAfterTheQuotaAndTheConsumptionAndBeforeTheProvider() {
        assertThatThrownBy(
                        () ->
                                useCase(false)
                                        .handle(
                                                original(),
                                                "proof",
                                                null,
                                                "code",
                                                "state",
                                                null,
                                                null,
                                                null,
                                                "127.0.0.1"))
                .as("callback")
                .isInstanceOfSatisfying(
                        GoogleAuthenticationFailedException.class,
                        error ->
                                assertThat(error.reason())
                                        .as("reason")
                                        .isEqualTo(GoogleAuthenticationFailedException.Reason.UNAVAILABLE));

        var order = inOrder(quota, transactions);
        order.verify(quota).check(QuotaScope.GOOGLE_CALLBACK, "browser:proof", "127.0.0.1");
        order.verify(transactions).consume("state", "proof", null);
        assertThat(client.verifications()).as("verifications").isZero();
        verify(transactions, never()).accept(any(), any(), any(), any(), any(), any());
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(
            value = {"sans parcours, false, state", "state faux, true, forged", "state absent, true, "})
    void callbackWithoutMatchingFlowIsRefusedBeforeAnyEffect(
            String ignored, boolean withFlow, String state) {
        assertThatThrownBy(
                        () ->
                                useCase(true)
                                        .handle(
                                                withFlow ? original() : null,
                                                withFlow ? "proof" : null,
                                                null,
                                                "code",
                                                state,
                                                null,
                                                null,
                                                null,
                                                "127.0.0.1"))
                .as("callback")
                .isInstanceOfSatisfying(
                        GoogleAuthenticationFailedException.class,
                        error ->
                                assertThat(error.reason())
                                        .as("reason")
                                        .isEqualTo(
                                                GoogleAuthenticationFailedException.Reason
                                                        .INVALID_FLOW));

        verifyNoInteractions(quota, transactions);
        assertThat(client.verifications()).as("verifications").isZero();
    }
}
