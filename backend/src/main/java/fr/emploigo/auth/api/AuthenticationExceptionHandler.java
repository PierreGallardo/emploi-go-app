package fr.emploigo.auth.api;

import fr.emploigo.auth.api.google.GoogleAuthenticationController;
import fr.emploigo.auth.api.recovery.PasswordRecoveryController;
import fr.emploigo.auth.api.registration.RegistrationController;
import fr.emploigo.auth.api.session.AuthenticationController;
import fr.emploigo.auth.application.failure.AuthenticationFailedException;
import fr.emploigo.auth.application.google.GoogleAuthenticationFailedException;
import fr.emploigo.auth.application.password.PasswordHashingBusyException;
import fr.emploigo.auth.application.recovery.PasswordRecoveryFailedException;
import fr.emploigo.auth.application.registration.RegistrationFailedException;
import fr.emploigo.config.ApiOnly;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Duration;

@RestControllerAdvice(
        assignableTypes = {
            AuthenticationController.class,
            RegistrationController.class,
            PasswordRecoveryController.class,
            GoogleAuthenticationController.class
        })
@ApiOnly
public class AuthenticationExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(AuthenticationExceptionHandler.class);
    static final String WEAK_PASSWORD_MESSAGE =
            "Ce mot de passe ne respecte pas la règle : 8 à 128 caractères,"
                    + " avec une majuscule, une minuscule, un chiffre et un caractère spécial.";
    static final String INVALID_USERNAME_MESSAGE =
            "2 à 32 caractères : lettres sans accent, chiffres, _ et .";

    @ExceptionHandler(GoogleAuthenticationFailedException.class)
    ResponseEntity<AuthenticationErrorResponse> google(GoogleAuthenticationFailedException error) {
        if (error.reason() == GoogleAuthenticationFailedException.Reason.INVALID_USERNAME)
            return response(422, "INVALID_USERNAME", INVALID_USERNAME_MESSAGE);
        int status =
                switch (error.reason()) {
                    case UNAVAILABLE -> 503;
                    case EXPIRED_FLOW, EXPIRED_LINK, USED_LINK, REPLACED_LINK -> 410;
                    case ACCOUNT_ACCESS_REQUIRED, ALREADY_LINKED, USERNAME_UNAVAILABLE -> 409;
                    default -> 400;
                };
        return response(
                status,
                error.reason().name(),
                "Le parcours Google n'a pas pu être finalisé. Vérifie l'étape ou recommence.");
    }

    @ExceptionHandler(PasswordRecoveryFailedException.class)
    ResponseEntity<AuthenticationErrorResponse> recovery(PasswordRecoveryFailedException error) {
        return switch (error.reason()) {
            case INVALID_INPUT ->
                    response(400, "INVALID_INPUT", "Vérifie les champs du formulaire.");
            case WEAK_PASSWORD -> response(400, "WEAK_PASSWORD", WEAK_PASSWORD_MESSAGE);
            case INVALID_LINK ->
                    response(400, "INVALID_LINK", "Ce lien est invalide. Demande un nouveau lien.");
            case EXPIRED_LINK ->
                    response(
                            410,
                            "EXPIRED_LINK",
                            "Ce lien a expiré. Demande une nouvelle récupération.");
            case USED_LINK ->
                    response(
                            410,
                            "USED_LINK",
                            "Ce lien a déjà été utilisé. "
                                    + "Connecte-toi ou demande une nouvelle récupération.");
            case REPLACED_LINK ->
                    response(410, "REPLACED_LINK", "Utilise le lien le plus récemment demandé.");
            case UNAVAILABLE ->
                    response(
                            503,
                            "UNAVAILABLE",
                            "Récupération temporairement indisponible. Réessaie plus tard.");
        };
    }

    @ExceptionHandler(RegistrationFailedException.class)
    ResponseEntity<AuthenticationErrorResponse> registration(RegistrationFailedException error) {
        return switch (error.reason()) {
            case INVALID_INPUT ->
                    response(400, "INVALID_INPUT", "Vérifie les champs du formulaire.");
            case WEAK_PASSWORD -> response(400, "WEAK_PASSWORD", WEAK_PASSWORD_MESSAGE);
            case INVALID_USERNAME ->
                    response(422, "INVALID_USERNAME", INVALID_USERNAME_MESSAGE);
            case USERNAME_UNAVAILABLE ->
                    response(409, "USERNAME_UNAVAILABLE", "Ce pseudo est déjà pris.");
            case USERNAME_ALREADY_CHOSEN ->
                    response(409, "USERNAME_ALREADY_CHOSEN", "Ton pseudo est déjà choisi.");
            case ACCOUNT_ALREADY_EXISTS ->
                    response(
                            409,
                            "ACCOUNT_ALREADY_EXISTS",
                            "Un compte utilise déjà cette adresse. "
                                    + "Connecte-toi avec sa méthode habituelle.");
            case INVALID_CODE ->
                    response(
                            400,
                            "INVALID_CODE",
                            "Ce code n'est pas le bon. Vérifie le dernier email reçu et réessaie.");
            case CODE_EXPIRED ->
                    response(410, "CODE_EXPIRED", "Ce code a expiré. Demande un nouveau code.");
            case CODE_REPLACED ->
                    response(
                            410,
                            "CODE_REPLACED",
                            "Un nouveau code a remplacé celui-ci. "
                                    + "Utilise le dernier email reçu, ou demande un nouveau code.");
            case CODE_USED ->
                    response(
                            410,
                            "CODE_USED",
                            "Ce code a déjà servi. Connecte-toi avec ton email et ton mot de"
                                    + " passe.");
            case CODE_ATTEMPTS_EXCEEDED ->
                    response(
                            410,
                            "CODE_ATTEMPTS_EXCEEDED",
                            "Trop d'essais : ce code ne fonctionne plus. Demande un nouveau code.");
            case NO_PENDING_REGISTRATION ->
                    response(
                            410,
                            "NO_PENDING_REGISTRATION",
                            "Aucune inscription en cours dans ce navigateur. Recommence"
                                    + " l'inscription.");
            case UNAVAILABLE ->
                    response(
                            503,
                            "UNAVAILABLE",
                            "Inscription temporairement indisponible. Réessaie plus tard.");
        };
    }

    @ExceptionHandler(AuthenticationFailedException.class)
    ResponseEntity<AuthenticationErrorResponse> rejected(AuthenticationFailedException error) {
        return switch (error.reason()) {
            case INVALID_CREDENTIALS ->
                    response(401, "INVALID_CREDENTIALS", "Identifiant ou mot de passe incorrect.");
            case RECONNECTION_REFUSED ->
                    response(
                            401,
                            "RECONNECTION_REFUSED",
                            "Ta reconnexion automatique n'est plus valable. Connecte-toi de"
                                    + " nouveau.");
            case UNAUTHENTICATED ->
                    response(401, "UNAUTHENTICATED", "Connecte-toi pour continuer.");
            case RATE_LIMITED ->
                    ResponseEntity.status(429)
                            .header("Cache-Control", "no-store")
                            .header("Retry-After", retryAfterSeconds(error.retryAfter()))
                            .body(
                                    new AuthenticationErrorResponse(
                                            "RATE_LIMITED",
                                            "Trop de tentatives. Réessaie plus tard."));
            case MAIL_RECENTLY_SENT ->
                    ResponseEntity.status(429)
                            .header("Cache-Control", "no-store")
                            .header("Retry-After", retryAfterSeconds(error.retryAfter()))
                            .body(
                                    new AuthenticationErrorResponse(
                                            "MAIL_RECENTLY_SENT",
                                            "Un email a déjà été demandé pour cette adresse il y a"
                                                    + " moins d'une minute."));
            case CODES_BLOCKED ->
                    ResponseEntity.status(429)
                            .header("Cache-Control", "no-store")
                            .header("Retry-After", retryAfterSeconds(error.retryAfter()))
                            .body(
                                    new AuthenticationErrorResponse(
                                            "CODES_BLOCKED",
                                            "Trop d'essais. Plus aucun code pendant un moment."));
            case UNAVAILABLE ->
                    ResponseEntity.status(503)
                            .header("Cache-Control", "no-store")
                            .header("Retry-After", "5")
                            .body(
                                    new AuthenticationErrorResponse(
                                            "UNAVAILABLE",
                                            "Action temporairement indisponible. "
                                                    + "Réessaie dans quelques secondes."));
        };
    }

    private static String retryAfterSeconds(Duration wait) {
        return Long.toString(Math.max(1, (wait.toMillis() + 999) / 1000));
    }

    /**
     * The limit of simultaneous password computations: the same answer for login, signup, reset and
     * Google access. Its own code, so that a page tells the few seconds of Retry-After from the
     * outage of a dependency.
     */
    @ExceptionHandler(PasswordHashingBusyException.class)
    ResponseEntity<AuthenticationErrorResponse> busy() {
        return ResponseEntity.status(503)
                .header("Cache-Control", "no-store")
                .header("Retry-After", "5")
                .body(
                        new AuthenticationErrorResponse(
                                "BUSY",
                                "Le service est très sollicité. Réessaie dans quelques secondes."));
    }

    @ExceptionHandler({
        MethodArgumentNotValidException.class,
        HttpMessageNotReadableException.class
    })
    ResponseEntity<AuthenticationErrorResponse> invalid() {
        return response(400, "INVALID_INPUT", "Vérifie les champs du formulaire.");
    }

    @ExceptionHandler({
        DataAccessException.class,
        org.springframework.transaction.TransactionException.class
    })
    ResponseEntity<AuthenticationErrorResponse> unavailable(RuntimeException error) {
        LOG.error("Authentification indisponible ({})", error.getClass().getSimpleName());
        return response(
                503, "UNAVAILABLE", "Service temporairement indisponible. Réessaie plus tard.");
    }

    private ResponseEntity<AuthenticationErrorResponse> response(
            int status, String code, String message) {
        return ResponseEntity.status(status)
                .header("Cache-Control", "no-store")
                .body(new AuthenticationErrorResponse(code, message));
    }
}
