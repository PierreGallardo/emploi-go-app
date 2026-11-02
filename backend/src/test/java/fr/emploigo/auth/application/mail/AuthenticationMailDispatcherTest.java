package fr.emploigo.auth.application.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import fr.emploigo.auth.application.confirmation.ConfirmationKeySettings;
import fr.emploigo.auth.application.confirmation.ConfirmationTokens;
import fr.emploigo.auth.application.google.FakeGoogleRepository;
import fr.emploigo.auth.application.google.GoogleMailOperationChecker;
import fr.emploigo.auth.application.mail.AuthenticationMailSender.Result;
import fr.emploigo.auth.application.mail.FakeAuthenticationMailSender.Sent;
import fr.emploigo.auth.application.recovery.FakePasswordRecoveryRepository;
import fr.emploigo.auth.application.recovery.PasswordRecoveryRepository.Reset;
import fr.emploigo.auth.application.recovery.RecoveryMailOperationChecker;
import fr.emploigo.auth.application.registration.FakeRegistrationRepository;
import fr.emploigo.auth.application.registration.RegistrationMailOperationChecker;
import fr.emploigo.auth.domain.GoogleIdentity;
import fr.emploigo.auth.domain.MailFlow;
import fr.emploigo.auth.domain.RegistrationCode;
import fr.emploigo.support.TestClock;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

class AuthenticationMailDispatcherTest {
    private static final String ADDRESS = MailFixtures.ADDRESS;
    private static final String OTHER_KEY = "another-key";
    private static final long ACCOUNT = 1;
    private static final GoogleIdentity IDENTITY =
            new GoogleIdentity("subject", ADDRESS, false);

    private enum Link {
        SIGNUP(MailFlow.CONFIRMATION, "CONFIRMATION"),
        RECOVERY(MailFlow.RECOVERY, "RESET"),
        GOOGLE(MailFlow.GOOGLE, "VERIFY");

        private final MailFlow flow;
        private final String kind;

        Link(MailFlow flow, String kind) {
            this.flow = flow;
            this.kind = kind;
        }
    }

    /** A checker whose store is down, as the database is when the operation is read. */
    private static final class UnavailableChecker implements MailOperationChecker {
        @Override
        public MailFlow flow() {
            return MailFlow.CONFIRMATION;
        }

        @Override
        public String operationKind() {
            return "CONFIRMATION";
        }

        @Override
        public boolean pending(UUID operation, Instant now) {
            throw new DataAccessResourceFailureException("fixture unavailable");
        }
    }

    private final TestClock clock = new TestClock();
    private final FakeAuthenticationMailSender transport = new FakeAuthenticationMailSender();
    private final FakeRegistrationRepository registrations = new FakeRegistrationRepository();
    private final FakePasswordRecoveryRepository recoveries = new FakePasswordRecoveryRepository();
    private final FakeGoogleRepository google = new FakeGoogleRepository();
    private final ConfirmationTokens tokens =
            new ConfirmationTokens(new ConfirmationKeySettings(true, new byte[32]));

    private AuthenticationMailDispatcher dispatcher(List<MailOperationChecker> checkers) {
        return new AuthenticationMailDispatcher(
                new AuthenticationMailRenderer(tokens), transport, checkers, tokens, clock);
    }

    private final AuthenticationMailDispatcher dispatcher =
            dispatcher(
                    List.of(
                            new RegistrationMailOperationChecker(registrations),
                            new RecoveryMailOperationChecker(recoveries),
                            new GoogleMailOperationChecker(google)));

    @Test
    void dispatcherRefusesAFlowWithoutItsChecker() {
        var checkers =
                List.of(
                        new RegistrationMailOperationChecker(registrations),
                        new RecoveryMailOperationChecker(recoveries));

        assertThatThrownBy(() -> dispatcher(checkers))
                .as("construction")
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void dispatcherRefusesTwoCheckersOfTheSameFlow() {
        var checkers =
                List.of(
                        new RegistrationMailOperationChecker(registrations),
                        new RegistrationMailOperationChecker(registrations),
                        new RecoveryMailOperationChecker(recoveries),
                        new GoogleMailOperationChecker(google));

        assertThatThrownBy(() -> dispatcher(checkers))
                .as("construction")
                .isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest
    @CsvSource({
        "CONFIRMATION, NOTICE",
        "RECOVERY, NOTICE",
        "RECOVERY, GOOGLE",
        "RECOVERY, CHANGED",
        "GOOGLE, LINKED"
    })
    void aNoticeWithoutOperationIsSentToItsRecipient(MailFlow flow, String kind) {
        var mail = MailFixtures.mail(flow, kind, null, tokens.keyId());

        boolean accepted = dispatcher.send(mail);

        assertThat(accepted).as("accepted").isTrue();
        assertThat(transport.sent())
                .as("messages sent")
                .extracting(Sent::recipient, Sent::sender)
                .containsExactly(tuple(ADDRESS, MailFixtures.SENDER));
    }

    @Test
    void aMailIsSentUnderAnIdempotencyKeyOfItsOwnId() {
        var mail = MailFixtures.mail(MailFlow.CONFIRMATION, "NOTICE", null, tokens.keyId());

        dispatcher.send(mail);

        assertThat(transport.sent())
                .as("messages sent")
                .extracting(Sent::idempotencyKey)
                .containsExactly("registration-mail/" + mail.id());
    }

    @Test
    void aMailSentTwiceIsPresentedToTheProviderAsTheSameMessage() {
        var mail = MailFixtures.mail(MailFlow.CONFIRMATION, "NOTICE", null, tokens.keyId());

        dispatcher.send(mail);
        dispatcher.send(mail);

        assertThat(transport.sent())
                .as("messages sent")
                .extracting(Sent::idempotencyKey)
                .containsExactly(
                        "registration-mail/" + mail.id(), "registration-mail/" + mail.id());
    }

    @ParameterizedTest
    @EnumSource(
            value = Result.class,
            names = {"FAILED", "UNCERTAIN"})
    void aSendTheProviderDoesNotAcceptIsNotAccepted(Result result) {
        var mail = MailFixtures.mail(MailFlow.CONFIRMATION, "NOTICE", null, tokens.keyId());
        transport.answer(result);

        boolean accepted = dispatcher.send(mail);

        assertThat(accepted).as("accepted").isFalse();
    }

    @ParameterizedTest
    @EnumSource(Link.class)
    void aLinkWhoseOperationIsPendingIsSent(Link link) {
        var mail = linkTo(link, pendingOperation(link));

        boolean accepted = dispatcher.send(mail);

        assertThat(accepted).as("accepted").isTrue();
        assertThat(transport.sent()).as("messages sent").hasSize(1);
    }

    @ParameterizedTest
    @EnumSource(Link.class)
    void aLinkWhoseOperationIsUsedIsNotSent(Link link) {
        UUID operation = pendingOperation(link);
        var mail = linkTo(link, operation);
        use(link, operation);

        assertNotSent(mail);
    }

    @ParameterizedTest
    @EnumSource(Link.class)
    void aLinkWhoseOperationIsReplacedIsNotSent(Link link) {
        UUID operation = pendingOperation(link);
        var mail = linkTo(link, operation);
        replace(link, operation);

        assertNotSent(mail);
    }

    @ParameterizedTest
    @EnumSource(Link.class)
    void aLinkWhoseOperationHasExpiredIsNotSent(Link link) {
        var mail = linkTo(link, pendingOperation(link));
        clock.now = clock.now.plus(Duration.ofHours(2));

        assertNotSent(mail);
    }

    @ParameterizedTest
    @EnumSource(Link.class)
    void aLinkWhoseOperationIsGoneIsNotSent(Link link) {
        var mail = linkTo(link, UUID.randomUUID());

        assertNotSent(mail);
    }

    @ParameterizedTest
    @EnumSource(Link.class)
    void aLinkWithoutOperationIsNotSent(Link link) {
        var mail = linkTo(link, null);

        assertNotSent(mail);
    }

    @Test
    void aCodeWhoseAttemptsAreSpentIsNotSent() {
        UUID operation = pendingOperation(Link.SIGNUP);
        var mail = linkTo(Link.SIGNUP, operation);
        UUID registration = registrations.verification(operation).registrationId();
        Instant until = clock.instant().plusSeconds(60);
        registrations.blockCodes(operation, registration, until, RegistrationCode.MAX_ATTEMPTS);

        assertNotSent(mail);
    }

    @ParameterizedTest
    @EnumSource(MailFlow.class)
    void aMailDecidedUnderAnotherKeyIsNotSent(MailFlow flow) {
        var mail = MailFixtures.mail(flow, "NOTICE", null, OTHER_KEY);

        assertNotSent(mail);
    }

    @Test
    void aMailWhoseOperationCannotBeReadIsNotSent() {
        var unavailable =
                dispatcher(
                        List.of(
                                new UnavailableChecker(),
                                new RecoveryMailOperationChecker(recoveries),
                                new GoogleMailOperationChecker(google)));
        var mail = linkTo(Link.SIGNUP, UUID.randomUUID());

        boolean accepted = unavailable.send(mail);

        assertThat(accepted).as("accepted").isFalse();
        assertThat(transport.sent()).as("messages sent").isEmpty();
    }

    private void assertNotSent(AuthenticationMail mail) {
        assertThat(dispatcher.send(mail)).as("accepted").isFalse();
        assertThat(transport.sent()).as("messages sent").isEmpty();
    }

    private AuthenticationMail linkTo(Link link, UUID operation) {
        return MailFixtures.mail(link.flow, link.kind, operation, tokens.keyId());
    }

    private UUID pendingOperation(Link link) {
        UUID operation = UUID.randomUUID();
        Instant now = clock.instant();
        Instant expiry = now.plus(Duration.ofHours(1));
        String keyId = tokens.keyId();
        switch (link) {
            case SIGNUP -> {
                UUID registration = UUID.randomUUID();
                registrations.insert(registration, ADDRESS, "hash", "key", expiry);
                registrations.addVerification(operation, registration, "hash", keyId, now, expiry);
            }
            case RECOVERY ->
                    recoveries.store(
                            new Reset(
                                    operation, ACCOUNT, "hash", keyId, 0, expiry, "PENDING", null));
            case GOOGLE -> google.prepareVerification(operation, IDENTITY, "hash", keyId, expiry);
        }
        return operation;
    }

    private void use(Link link, UUID operation) {
        switch (link) {
            case SIGNUP ->
                    registrations.consume(
                            operation,
                            registrations.verification(operation).registrationId(),
                            ACCOUNT);
            case RECOVERY -> recoveries.consume(recoveries.reset(operation), clock.instant());
            case GOOGLE -> google.confirm(operation, clock.instant());
        }
    }

    private void replace(Link link, UUID operation) {
        switch (link) {
            case SIGNUP ->
                    registrations.replaceVerification(
                            registrations.verification(operation).registrationId());
            case RECOVERY -> recoveries.replace(ACCOUNT);
            case GOOGLE ->
                    google.prepareVerification(
                            UUID.randomUUID(),
                            IDENTITY,
                            "hash",
                            tokens.keyId(),
                            clock.instant().plus(Duration.ofHours(1)));
        }
    }
}
