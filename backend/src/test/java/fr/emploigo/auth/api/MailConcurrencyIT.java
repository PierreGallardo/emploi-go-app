package fr.emploigo.auth.api;

import static fr.emploigo.support.FakeProviders.MAIL_RESPONSE;
import static fr.emploigo.support.FakeProviders.mailStatus;

import static org.assertj.core.api.Assertions.assertThat;

import fr.emploigo.support.MailCounters;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.net.http.HttpResponse;
import java.util.concurrent.Executors;

/**
 * An address is sent one email per delay, set before the provider is called: until the delay is
 * over, another request for it is answered 429 without calling the provider, even while the first
 * email is still being sent. The wiring of the three flows is checked by the parameterized tests;
 * the others run on one flow, as the rules do not depend on it.
 */
class MailConcurrencyIT extends AuthMailIT {
    private static final SendingFlow FLOW = SendingFlow.SIGNUP;

    @ParameterizedTest(name = "{0}")
    @EnumSource(SendingFlow.class)
    void aSecondRequestDuringTheSendAnswers429WithoutASecondProviderCall(SendingFlow flow)
            throws Exception {
        flow.given(this);
        var mail = new MailCounters();
        HttpResponse<String> during;

        try (var pool = Executors.newVirtualThreadPerTaskExecutor();
                var hold = MAIL_RESPONSE.hold()) {
            var request = pool.submit(() -> flow.request(this));
            hold.awaitRequests(1);

            during = flow.request(this);
            hold.release();
            request.get();
        }

        assertThat(during.statusCode()).as("second request during the send").isEqualTo(429);
        assertThat(code(during))
                .as("error code of the second request")
                .isEqualTo("MAIL_RECENTLY_SENT");
        assertThat(mail.calls()).as("provider calls").isEqualTo(1);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(SendingFlow.class)
    void aSecondRequestAfterTheSendWithinTheDelayAnswers429WithoutASecondProviderCall(
            SendingFlow flow) throws Exception {
        flow.given(this);
        assertThat(flow.request(this).statusCode()).as("given: email accepted").isEqualTo(202);
        var mail = new MailCounters();

        var next = flow.request(this);

        assertThat(next.statusCode()).as("status").isEqualTo(429);
        assertThat(code(next)).as("error code").isEqualTo("MAIL_RECENTLY_SENT");
        assertThat(mail.calls()).as("provider calls").isZero();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(SendingFlow.class)
    void aRequestOnceTheDelayIsOverAnswers202AndSendsAnotherEmail(SendingFlow flow)
            throws Exception {
        flow.given(this);
        var mail = new MailCounters();
        assertThat(flow.request(this).statusCode()).as("given: email accepted").isEqualTo(202);
        flow.permitNextMail(this);

        var next = flow.request(this);

        assertThat(next.statusCode()).as("status").isEqualTo(202);
        assertThat(mail.sends()).as("distinct messages: a key per email").isEqualTo(2);
    }

    @Test
    void aRequestOnceTheDelayIsOverAfterAProviderErrorAnswers202() throws Exception {
        FLOW.given(this);
        mailStatus = 500;
        assertThat(FLOW.request(this).statusCode()).as("given: provider error").isEqualTo(503);
        mailStatus = 200;
        FLOW.permitNextMail(this);

        var next = FLOW.request(this);

        assertThat(next.statusCode()).as("status").isEqualTo(202);
    }

    @Test
    void aProviderRefusalAnswers503() throws Exception {
        FLOW.given(this);
        mailStatus = 403;

        var refused = FLOW.request(this);

        assertThat(refused.statusCode()).as("status").isEqualTo(503);
        assertThat(code(refused)).as("error code").isEqualTo("UNAVAILABLE");
    }
}
