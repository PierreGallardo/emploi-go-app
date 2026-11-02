package fr.emploigo.auth.application.mail;

import static org.assertj.core.api.Assertions.assertThat;

import fr.emploigo.auth.application.AuthenticationIntegrationTest;
import fr.emploigo.auth.application.confirmation.ConfirmationTokens;
import fr.emploigo.auth.domain.MailFlow;
import fr.emploigo.support.MailCounters;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

/**
 * The single sending service against the real operations and the provider, one case per path: a
 * refusal calls no provider. The rules are tested once, in {@link
 * AuthenticationMailDispatcherTest}.
 */
class AuthenticationMailDispatcherIT extends AuthenticationIntegrationTest {
    private static final MailFlow FLOW = MailFlow.CONFIRMATION;
    private static final String KIND = "CONFIRMATION";

    @Autowired AuthenticationMailDispatcher mail;
    @Autowired ConfirmationTokens tokens;

    @Test
    void aLinkWhoseOperationIsPendingIsSentToTheProvider() {
        UUID operation = MailFixtures.pendingVerification(jdbc, clock.instant(), tokens.keyId());
        var calls = new MailCounters();

        boolean accepted = mail.send(MailFixtures.mail(FLOW, KIND, operation, tokens.keyId()));

        assertThat(accepted).as("accepted").isTrue();
        assertThat(calls.calls()).as("provider calls").isEqualTo(1);
    }

    @Test
    void aLinkWhoseOperationIsNoLongerPendingIsNotSent() {
        UUID operation = MailFixtures.pendingVerification(jdbc, clock.instant(), tokens.keyId());
        jdbc.update("UPDATE auth_verifications SET status = 'USED' WHERE id = ?", operation);
        var calls = new MailCounters();

        boolean accepted = mail.send(MailFixtures.mail(FLOW, KIND, operation, tokens.keyId()));

        assertThat(accepted).as("accepted").isFalse();
        assertThat(calls.calls()).as("provider calls").isZero();
    }

    @Test
    void aMailDecidedUnderAnotherKeyIsNotSent() {
        var calls = new MailCounters();

        boolean accepted = mail.send(MailFixtures.mail(FLOW, "NOTICE", null, "another-key"));

        assertThat(accepted).as("accepted").isFalse();
        assertThat(calls.calls()).as("provider calls").isZero();
    }
}
