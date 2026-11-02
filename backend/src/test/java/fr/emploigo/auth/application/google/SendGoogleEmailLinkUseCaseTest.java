package fr.emploigo.auth.application.google;

import static fr.emploigo.auth.application.google.GoogleFlowFixture.ADDRESS;
import static fr.emploigo.auth.application.google.GoogleFlowFixture.assertRefusedBecause;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import fr.emploigo.auth.application.failure.AuthenticationFailedException;
import fr.emploigo.auth.application.google.GoogleAuthenticationFailedException.Reason;
import fr.emploigo.auth.application.mail.AuthenticationMailSender.Result;
import fr.emploigo.auth.application.quota.AuthenticationRateLimiter.Cause;
import fr.emploigo.auth.application.quota.AuthenticationRateLimiter.Limited;
import fr.emploigo.auth.application.quota.FakeAuthenticationRateLimiter.Counted;
import fr.emploigo.auth.application.quota.QuotaScope;
import fr.emploigo.auth.domain.GoogleIdentity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Duration;

class SendGoogleEmailLinkUseCaseTest {
    private static final GoogleIdentity UNCONFIRMED =
            new GoogleIdentity("sub-courriel", "jeune@example.invalid", false);

    private final GoogleFlowFixture f = new GoogleFlowFixture();

    private void send(GoogleTransactions.Callback pending, String proof, GoogleSettings settings) {
        new SendGoogleEmailLinkUseCase(f.transactions, f.quota, f.dispatcher, settings)
                .send(pending.pendingId(), proof, null, ADDRESS);
    }

    @Test
    void anEmailLinkIsSentToTheAddressOfThePendingFlow() {
        var pending = f.pendingFor(UNCONFIRMED);

        send(pending, pending.pendingProof(), f.settings);

        assertThat(f.transport.sent()).as("messages sent").hasSize(1);
        assertThat(f.transport.sent().get(0).recipient())
                .as("recipient")
                .isEqualTo("jeune@example.invalid");
        assertThat(f.transport.sent().get(0).subject())
                .as("subject")
                .isEqualTo("Confirme ton email pour Google sur Emploi Go");
    }

    @Test
    void theAttemptIsCountedUnderTheAddressToSend() {
        var pending = f.pendingFor(UNCONFIRMED);

        send(pending, pending.pendingProof(), f.settings);

        assertThat(f.limiter.counted())
                .as("counted attempts")
                .contains(new Counted(QuotaScope.GOOGLE_MAIL, "jeune@example.invalid", ADDRESS));
    }

    @ParameterizedTest
    @EnumSource(
            value = Result.class,
            names = {"FAILED", "UNCERTAIN"})
    void aProviderThatDoesNotAcceptTheEmailIsReportedAsUnavailable(Result result) {
        var pending = f.pendingFor(UNCONFIRMED);
        f.transport.answer(result);

        assertRefusedBecause(
                () -> send(pending, pending.pendingProof(), f.settings), Reason.UNAVAILABLE);
    }

    @Test
    void aVerificationThatLosesToASimultaneousOneIsReportedAsUnavailableAndSendsNothing() {
        var pending = f.pendingFor(UNCONFIRMED);
        f.google.conflictOnNextVerification();

        assertRefusedBecause(
                () -> send(pending, pending.pendingProof(), f.settings), Reason.UNAVAILABLE);
        assertThat(f.transport.sent()).as("messages sent").isEmpty();
    }

    @Test
    void aDisabledGoogleSendsNothing() {
        var pending = f.pendingFor(UNCONFIRMED);
        var disabled = new GoogleSettings(false, GoogleFlowFixture.ORIGIN);

        assertRefusedBecause(
                () -> send(pending, pending.pendingProof(), disabled), Reason.UNAVAILABLE);

        assertThat(f.transport.sent()).as("messages sent").isEmpty();
    }

    @Test
    void aRefusedQuotaSendsNothing() {
        var pending = f.pendingFor(UNCONFIRMED);
        f.limiter.answer(QuotaScope.GOOGLE_MAIL, new Limited(Duration.ofMinutes(1), Cause.VOLUME));

        assertThatThrownBy(() -> send(pending, pending.pendingProof(), f.settings))
                .as("send")
                .isInstanceOf(AuthenticationFailedException.class);

        assertThat(f.transport.sent()).as("messages sent").isEmpty();
    }

    @Test
    void aFlowOfAnotherBrowserIsRefusedAsInvalidFlow() {
        var pending = f.pendingFor(UNCONFIRMED);

        assertRefusedBecause(
                () -> send(pending, "autre-navigateur", f.settings), Reason.INVALID_FLOW);

        assertThat(f.transport.sent()).as("messages sent").isEmpty();
    }
}
