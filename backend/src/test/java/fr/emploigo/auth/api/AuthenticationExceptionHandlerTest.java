package fr.emploigo.auth.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import fr.emploigo.auth.application.failure.AuthenticationFailedException;
import fr.emploigo.auth.application.google.GoogleAuthenticationFailedException;
import fr.emploigo.auth.application.recovery.PasswordRecoveryFailedException;
import fr.emploigo.auth.application.registration.RegistrationFailedException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.util.function.Function;
import java.util.stream.Stream;

class AuthenticationExceptionHandlerTest {
    private final AuthenticationExceptionHandler handler = new AuthenticationExceptionHandler();

    @Test
    void passwordHashingAtItsLimitAnswers503BusyWithRetryAfterAndNoStore() {
        var response = handler.busy();

        assertThat(response.getStatusCode().value()).as("status").isEqualTo(503);
        assertThat(response.getHeaders().getFirst("Retry-After")).as("Retry-After").isEqualTo("5");
        assertThat(response.getHeaders().getFirst("Cache-Control"))
                .as("Cache-Control")
                .isEqualTo("no-store");
        assertThat(response.getBody().code()).as("code").isEqualTo("BUSY");
    }

    @Test
    void databaseOutageAnswers503WithItsOwnMessageAndNoRetryAfter() {
        var response = handler.unavailable(new DataAccessResourceFailureException("down"));

        assertThat(response.getStatusCode().value()).as("status").isEqualTo(503);
        assertThat(response.getHeaders().containsHeader("Retry-After"))
                .as("has Retry-After")
                .isFalse();
        assertThat(response.getHeaders().getFirst("Cache-Control"))
                .as("Cache-Control")
                .isEqualTo("no-store");
        assertThat(response.getBody().code()).as("code").isEqualTo("UNAVAILABLE");
        assertThat(response.getBody().message())
                .as("message")
                .isEqualTo("Service temporairement indisponible. Réessaie plus tard.");
    }

    static Stream<Arguments> otherUnavailableAnswers() {
        return Stream.of(
                argumentSet(
                        "inscription indisponible",
                        (Function<AuthenticationExceptionHandler, ResponseEntity<?>>)
                                handler ->
                                        handler.registration(
                                                new RegistrationFailedException(
                                                        RegistrationFailedException.Reason.UNAVAILABLE))),
                argumentSet(
                        "récupération indisponible",
                        (Function<AuthenticationExceptionHandler, ResponseEntity<?>>)
                                handler ->
                                        handler.recovery(
                                                new PasswordRecoveryFailedException(
                                                        PasswordRecoveryFailedException.Reason.UNAVAILABLE))),
                argumentSet(
                        "parcours Google indisponible",
                        (Function<AuthenticationExceptionHandler, ResponseEntity<?>>)
                                handler ->
                                        handler.google(
                                                new GoogleAuthenticationFailedException(
                                                        GoogleAuthenticationFailedException.Reason.UNAVAILABLE))));
    }

    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("otherUnavailableAnswers")
    void otherUnavailableAnswersKeepNoRetryAfter(
            Function<AuthenticationExceptionHandler, ResponseEntity<?>> answer) {
        var response = answer.apply(handler);

        assertThat(response.getStatusCode().value()).as("status").isEqualTo(503);
        assertThat(response.getHeaders().containsHeader("Retry-After"))
                .as("has Retry-After")
                .isFalse();
    }

    static Stream<Arguments> waits() {
        return Stream.of(
                argumentSet("14 minutes et demie", Duration.ofSeconds(870), "870"),
                argumentSet("une fraction de seconde", Duration.ofMillis(300), "1"),
                argumentSet("arrondi à la seconde supérieure", Duration.ofMillis(61_200), "62"));
    }

    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("waits")
    void quotaReachedAnswers429WithTheWaitOfTheCounterAndNoFixedDuration(
            Duration wait, String seconds) {
        var response = handler.rejected(AuthenticationFailedException.rateLimited(wait));

        assertThat(response.getStatusCode().value()).as("status").isEqualTo(429);
        assertThat(response.getHeaders().getFirst("Retry-After"))
                .as("Retry-After")
                .isEqualTo(seconds);
        assertThat(response.getHeaders().getFirst("Cache-Control"))
                .as("Cache-Control")
                .isEqualTo("no-store");
        assertThat(response.getBody().code()).as("code").isEqualTo("RATE_LIMITED");
        assertThat(response.getBody().message()).as("message").doesNotContain("15 minutes");
    }

    @Test
    void mailRecentlySentAnswers429WithItsCodeItsMessageAndTheWaitOfTheDelay() {
        var response =
                handler.rejected(
                        AuthenticationFailedException.mailRecentlySent(Duration.ofSeconds(42)));

        assertThat(response.getStatusCode().value()).as("status").isEqualTo(429);
        assertThat(response.getHeaders().getFirst("Retry-After")).as("Retry-After").isEqualTo("42");
        assertThat(response.getHeaders().getFirst("Cache-Control"))
                .as("Cache-Control")
                .isEqualTo("no-store");
        assertThat(response.getBody().code()).as("code").isEqualTo("MAIL_RECENTLY_SENT");
        assertThat(response.getBody().message())
                .as("message")
                .isEqualTo("Un email a déjà été demandé pour cette adresse il y a moins d'une minute.");
    }

    @Test
    void blockedCodesAnswer429WithTheCodeOfTheBlockAndTheWait() {
        var wait = Duration.ofSeconds(870);

        var response = handler.rejected(AuthenticationFailedException.codesBlocked(wait));

        assertThat(response.getStatusCode().value()).as("status").isEqualTo(429);
        assertThat(response.getHeaders().getFirst("Retry-After")).as("Retry-After").isEqualTo("870");
        assertThat(response.getHeaders().getFirst("Cache-Control"))
                .as("Cache-Control")
                .isEqualTo("no-store");
        assertThat(response.getBody().code()).as("code").isEqualTo("CODES_BLOCKED");
    }

    @Test
    void unavailableCountersAnswer503WithRetryAfterAndNoStore() {
        var response = handler.rejected(AuthenticationFailedException.unavailable());

        assertThat(response.getStatusCode().value()).as("status").isEqualTo(503);
        assertThat(response.getHeaders().getFirst("Retry-After")).as("Retry-After").isEqualTo("5");
        assertThat(response.getHeaders().getFirst("Cache-Control"))
                .as("Cache-Control")
                .isEqualTo("no-store");
        assertThat(response.getBody().code()).as("code").isEqualTo("UNAVAILABLE");
        assertThat(response.getBody().message())
                .as("message")
                .isEqualTo("Action temporairement indisponible. Réessaie dans quelques secondes.");
    }

    @Test
    void refusedReconnectionAnswers401WithItsOwnCodeAndNoStore() {
        var response = handler.rejected(AuthenticationFailedException.reconnectionRefused());

        assertThat(response.getStatusCode().value()).as("status").isEqualTo(401);
        assertThat(response.getHeaders().getFirst("Cache-Control"))
                .as("Cache-Control")
                .isEqualTo("no-store");
        assertThat(response.getBody().code()).as("code").isEqualTo("RECONNECTION_REFUSED");
    }

    @Test
    void unauthenticatedSessionAnswers401WithTheCodeOfTheEntryPoint() {
        var response = handler.rejected(AuthenticationFailedException.unauthenticated());

        assertThat(response.getStatusCode().value()).as("status").isEqualTo(401);
        assertThat(response.getHeaders().getFirst("Cache-Control"))
                .as("Cache-Control")
                .isEqualTo("no-store");
        assertThat(response.getBody().code()).as("code").isEqualTo("UNAUTHENTICATED");
        assertThat(response.getBody().message())
                .as("message")
                .isEqualTo("Connecte-toi pour continuer.");
    }

    @Test
    void weakPasswordAnswers400WithTheRuleInBothFlows() {
        var registration =
                handler.registration(
                        new RegistrationFailedException(RegistrationFailedException.Reason.WEAK_PASSWORD));
        var recovery = handler.recovery(new PasswordRecoveryFailedException(PasswordRecoveryFailedException.Reason.WEAK_PASSWORD));

        // The frontend shows the same words for WEAK_PASSWORD (errors.test.ts holds the text).
        assertThat(Stream.of(registration, recovery))
                .as("answers")
                .allSatisfy(
                        response -> {
                            assertThat(response.getStatusCode().value())
                                    .as("status")
                                    .isEqualTo(400);
                            assertThat(response.getBody().code())
                                    .as("code")
                                    .isEqualTo("WEAK_PASSWORD");
                            assertThat(response.getBody().message())
                                    .as("message")
                                    .isEqualTo(
                                            AuthenticationExceptionHandler.WEAK_PASSWORD_MESSAGE);
                        });
    }

    @Test
    void invalidUsernameAnswers422WithTheRuleInBothFlows() {
        var registration =
                handler.registration(
                        new RegistrationFailedException(
                                RegistrationFailedException.Reason.INVALID_USERNAME));
        var google =
                handler.google(
                        new GoogleAuthenticationFailedException(
                                GoogleAuthenticationFailedException.Reason.INVALID_USERNAME));

        // The frontend shows the same words for INVALID_USERNAME (errors.test.ts holds the text).
        assertThat(Stream.of(registration, google))
                .as("answers")
                .allSatisfy(
                        response -> {
                            assertThat(response.getStatusCode().value())
                                    .as("status")
                                    .isEqualTo(422);
                            assertThat(response.getBody().code())
                                    .as("code")
                                    .isEqualTo("INVALID_USERNAME");
                            assertThat(response.getBody().message())
                                    .as("message")
                                    .isEqualTo(
                                            "2 à 32 caractères : lettres sans accent,"
                                                    + " chiffres, _ et .");
                        });
    }

    static Stream<Arguments> registrationAnswers() {
        return Stream.of(
                argumentSet("code faux", RegistrationFailedException.Reason.INVALID_CODE, 400),
                argumentSet("code expiré", RegistrationFailedException.Reason.CODE_EXPIRED, 410),
                argumentSet("code remplacé", RegistrationFailedException.Reason.CODE_REPLACED, 410),
                argumentSet("code utilisé", RegistrationFailedException.Reason.CODE_USED, 410),
                argumentSet(
                        "essais épuisés",
                        RegistrationFailedException.Reason.CODE_ATTEMPTS_EXCEEDED,
                        410),
                argumentSet(
                        "pseudo pris",
                        RegistrationFailedException.Reason.USERNAME_UNAVAILABLE,
                        409),
                argumentSet(
                        "compte existant",
                        RegistrationFailedException.Reason.ACCOUNT_ALREADY_EXISTS,
                        409),
                argumentSet(
                        "entrée invalide", RegistrationFailedException.Reason.INVALID_INPUT, 400),
                argumentSet(
                        "mot de passe faible",
                        RegistrationFailedException.Reason.WEAK_PASSWORD,
                        400),
                argumentSet("indisponible", RegistrationFailedException.Reason.UNAVAILABLE, 503));
    }

    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("registrationAnswers")
    void registrationReasonAnswersItsStatusAndKeepsItsNameAsCode(
            RegistrationFailedException.Reason reason, int status) {
        var response = handler.registration(new RegistrationFailedException(reason));

        assertThat(response.getStatusCode().value()).as("status").isEqualTo(status);
        assertThat(response.getBody().code()).as("code").isEqualTo(reason.name());
        assertThat(response.getHeaders().getFirst("Cache-Control"))
                .as("Cache-Control")
                .isEqualTo("no-store");
    }

    static Stream<Arguments> recoveryAnswers() {
        return Stream.of(
                argumentSet("entrée invalide", PasswordRecoveryFailedException.Reason.INVALID_INPUT, 400),
                argumentSet("mot de passe faible", PasswordRecoveryFailedException.Reason.WEAK_PASSWORD, 400),
                argumentSet("lien invalide", PasswordRecoveryFailedException.Reason.INVALID_LINK, 400),
                argumentSet("lien expiré", PasswordRecoveryFailedException.Reason.EXPIRED_LINK, 410),
                argumentSet("lien utilisé", PasswordRecoveryFailedException.Reason.USED_LINK, 410),
                argumentSet(
                        "lien remplacé", PasswordRecoveryFailedException.Reason.REPLACED_LINK, 410),
                argumentSet(
                        "indisponible", PasswordRecoveryFailedException.Reason.UNAVAILABLE, 503));
    }

    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("recoveryAnswers")
    void recoveryReasonAnswersItsStatusAndKeepsItsNameAsCode(
            PasswordRecoveryFailedException.Reason reason, int status) {
        var response = handler.recovery(new PasswordRecoveryFailedException(reason));

        assertThat(response.getStatusCode().value()).as("status").isEqualTo(status);
        assertThat(response.getBody().code()).as("code").isEqualTo(reason.name());
        assertThat(response.getHeaders().getFirst("Cache-Control"))
                .as("Cache-Control")
                .isEqualTo("no-store");
    }

    static Stream<Arguments> googleAnswers() {
        return Stream.of(
                argumentSet(
                        "entrée invalide",
                        GoogleAuthenticationFailedException.Reason.INVALID_INPUT,
                        400),
                argumentSet(
                        "parcours invalide",
                        GoogleAuthenticationFailedException.Reason.INVALID_FLOW,
                        400),
                argumentSet(
                        "parcours expiré",
                        GoogleAuthenticationFailedException.Reason.EXPIRED_FLOW,
                        410),
                argumentSet(
                        "parcours annulé",
                        GoogleAuthenticationFailedException.Reason.CANCELLED,
                        400),
                argumentSet(
                        "fournisseur refusé",
                        GoogleAuthenticationFailedException.Reason.PROVIDER_REJECTED,
                        400),
                argumentSet(
                        "accès au compte requis",
                        GoogleAuthenticationFailedException.Reason.ACCOUNT_ACCESS_REQUIRED,
                        409),
                argumentSet(
                        "email à confirmer", GoogleAuthenticationFailedException.Reason.EMAIL_CONFIRMATION_REQUIRED, 400),
                argumentSet("déjà lié", GoogleAuthenticationFailedException.Reason.ALREADY_LINKED, 409),
                argumentSet("pseudo pris", GoogleAuthenticationFailedException.Reason.USERNAME_UNAVAILABLE, 409),
                argumentSet("lien invalide", GoogleAuthenticationFailedException.Reason.INVALID_LINK, 400),
                argumentSet("lien expiré", GoogleAuthenticationFailedException.Reason.EXPIRED_LINK, 410),
                argumentSet("lien utilisé", GoogleAuthenticationFailedException.Reason.USED_LINK, 410),
                argumentSet("lien remplacé", GoogleAuthenticationFailedException.Reason.REPLACED_LINK, 410),
                argumentSet("indisponible", GoogleAuthenticationFailedException.Reason.UNAVAILABLE, 503));
    }

    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("googleAnswers")
    void googleReasonAnswersItsStatusAndKeepsItsNameAsCode(
            GoogleAuthenticationFailedException.Reason reason, int status) {
        var response = handler.google(new GoogleAuthenticationFailedException(reason));

        assertThat(response.getStatusCode().value()).as("status").isEqualTo(status);
        assertThat(response.getBody().code()).as("code").isEqualTo(reason.name());
        assertThat(response.getHeaders().getFirst("Cache-Control"))
                .as("Cache-Control")
                .isEqualTo("no-store");
    }

    @Test
    void unreadableOrInvalidBodyAnswers400WithNoStore() {
        var response = handler.invalid();

        assertThat(response.getStatusCode().value()).as("status").isEqualTo(400);
        assertThat(response.getBody().code()).as("code").isEqualTo("INVALID_INPUT");
        assertThat(response.getHeaders().getFirst("Cache-Control"))
                .as("Cache-Control")
                .isEqualTo("no-store");
    }
}
