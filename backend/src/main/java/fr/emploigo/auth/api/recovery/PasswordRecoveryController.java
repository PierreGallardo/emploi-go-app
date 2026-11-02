package fr.emploigo.auth.api.recovery;

import fr.emploigo.auth.api.AuthenticationErrorResponse;
import fr.emploigo.auth.api.session.BrowserAuthentication;
import fr.emploigo.auth.application.recovery.RequestPasswordResetUseCase;
import fr.emploigo.auth.application.recovery.ResetPasswordUseCase;
import fr.emploigo.config.ApiOnly;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ApiOnly
@RequestMapping("/api/auth/password")
public class PasswordRecoveryController {
    private final RequestPasswordResetUseCase requestReset;
    private final ResetPasswordUseCase resetPassword;
    private final BrowserAuthentication browser;

    public PasswordRecoveryController(
            RequestPasswordResetUseCase requestReset,
            ResetPasswordUseCase resetPassword,
            BrowserAuthentication browser) {
        this.requestReset = requestReset;
        this.resetPassword = resetPassword;
        this.browser = browser;
    }

    @PostMapping("/forgot")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(
            summary =
                    "Demander une récupération avec une réponse indépendante de l’existence du compte")
    @ApiResponse(
            responseCode = "202",
            description = "Si un compte correspond à cette adresse, un message sera envoyé",
            headers =
                    @Header(
                            name = "Retry-After",
                            description =
                                    "Attente avant un nouvel email à cette adresse, en secondes,"
                                            + " la même avec ou sans compte",
                            required = true,
                            schema = @Schema(type = "integer", format = "int64", minimum = "1")),
            content = @Content)
    @ApiResponse(
            responseCode = "400",
            description = "Entrée invalide (INVALID_INPUT)",
            content =
                    @Content(schema = @Schema(implementation = AuthenticationErrorResponse.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Jeton CSRF invalide",
            content =
                    @Content(schema = @Schema(implementation = AuthenticationErrorResponse.class)))
    @ApiResponse(
            responseCode = "429",
            description =
                    "Quota dépassé (RATE_LIMITED) ou un email vient d'être envoyé à cette adresse"
                            + " (MAIL_RECENTLY_SENT) : l'en-tête Retry-After indique l'attente en"
                            + " secondes",
            content =
                    @Content(schema = @Schema(implementation = AuthenticationErrorResponse.class)))
    @ApiResponse(
            responseCode = "503",
            description =
                    "Récupération indisponible (courriel désactivé ou non envoyé, base"
                            + " de données, compteurs) : Retry-After est présent pour les"
                            + " compteurs, absent pour le reste",
            content =
                    @Content(schema = @Schema(implementation = AuthenticationErrorResponse.class)))
    @io.swagger.v3.oas.annotations.Parameter(
            name = "X-CSRF-TOKEN",
            in = io.swagger.v3.oas.annotations.enums.ParameterIn.HEADER,
            required = true,
            schema = @Schema(type = "string"))
    public void request(
            @Valid @RequestBody RecoveryRequest body,
            HttpServletRequest request,
            HttpServletResponse response) {
        var wait = requestReset.request(body.email(), request.getRemoteAddr());
        response.setHeader("Retry-After", Long.toString(wait.toSeconds()));
    }

    @PostMapping("/reset")
    @Operation(
            summary =
                    "Changer le mot de passe et révoquer tous les anciens accès, sans connexion automatique")
    @ApiResponse(
            responseCode = "200",
            description = "Changement effectué ; la notification part une fois, sans reprise",
            content = @Content(schema = @Schema(implementation = PasswordResetResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description =
                    "Entrée invalide (INVALID_INPUT), mot de passe refusé"
                            + " (WEAK_PASSWORD) ou lien invalide (INVALID_LINK)",
            content =
                    @Content(schema = @Schema(implementation = AuthenticationErrorResponse.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Jeton CSRF invalide",
            content =
                    @Content(schema = @Schema(implementation = AuthenticationErrorResponse.class)))
    @ApiResponse(
            responseCode = "410",
            description = "Lien expiré, remplacé ou déjà consommé",
            content =
                    @Content(schema = @Schema(implementation = AuthenticationErrorResponse.class)))
    @ApiResponse(
            responseCode = "429",
            description = "Quota dépassé : l'en-tête Retry-After indique l'attente en secondes",
            content =
                    @Content(schema = @Schema(implementation = AuthenticationErrorResponse.class)))
    @ApiResponse(
            responseCode = "503",
            description =
                    "Récupération indisponible (courriel désactivé, base de données,"
                            + " compteurs) ou plafond de calculs atteint (BUSY) : Retry-After est"
                            + " présent pour les compteurs et le plafond, absent pour le reste",
            content =
                    @Content(schema = @Schema(implementation = AuthenticationErrorResponse.class)))
    @io.swagger.v3.oas.annotations.Parameter(
            name = "X-CSRF-TOKEN",
            in = io.swagger.v3.oas.annotations.enums.ParameterIn.HEADER,
            required = true,
            schema = @Schema(type = "string"))
    public PasswordResetResponse reset(
            @Valid @RequestBody PasswordResetRequest body,
            HttpServletRequest request,
            HttpServletResponse response) {
        resetPassword.reset(
                body.token(), body.password(), body.confirmation(), request.getRemoteAddr());
        browser.clear(request, response);
        return new PasswordResetResponse(true);
    }
}
