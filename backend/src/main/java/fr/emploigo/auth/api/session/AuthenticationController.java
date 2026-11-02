package fr.emploigo.auth.api.session;

import fr.emploigo.auth.api.AuthenticationErrorResponse;
import fr.emploigo.auth.application.failure.AuthenticationFailedException;
import fr.emploigo.auth.application.session.AuthenticationGrant;
import fr.emploigo.auth.application.session.GetSessionUseCase;
import fr.emploigo.auth.application.session.LogInWithPasswordUseCase;
import fr.emploigo.auth.application.session.ResumeSessionUseCase;
import fr.emploigo.auth.application.session.SessionPersistence;
import fr.emploigo.auth.domain.SessionIdentity;
import fr.emploigo.config.ApiOnly;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ApiOnly
@RequestMapping("/api/auth")
public class AuthenticationController {
    private final LogInWithPasswordUseCase logIn;
    private final GetSessionUseCase getSession;
    private final ResumeSessionUseCase resumeSession;
    private final BrowserAuthentication browser;

    public AuthenticationController(
            LogInWithPasswordUseCase logIn,
            GetSessionUseCase getSession,
            ResumeSessionUseCase resumeSession,
            BrowserAuthentication browser) {
        this.logIn = logIn;
        this.getSession = getSession;
        this.resumeSession = resumeSession;
        this.browser = browser;
    }

    @GetMapping("/csrf")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Poser le cookie du jeton CSRF s'il manque, sans créer de session")
    @ApiResponse(
            responseCode = "204",
            description = "Cookie CSRF présent dans le navigateur",
            content = @Content)
    @ApiResponse(
            responseCode = "503",
            description =
                    "Base de données indisponible : une requête qui porte un cookie de session le"
                            + " fait lire par AuthenticationSessionFilter. Sans cookie de session,"
                            + " la route ne touche aucune dépendance.",
            content =
                    @Content(schema = @Schema(implementation = AuthenticationErrorResponse.class)))
    public void csrf() {
        // Empty on purpose: the CSRF token is loaded eagerly, so CsrfFilter already wrote the
        // cookie if it was missing.
    }

    @PostMapping("/login")
    @Operation(summary = "Se connecter avec un email ou un pseudo et un mot de passe")
    @ApiResponse(
            responseCode = "200",
            description = "Compte connecté",
            content = @Content(schema = @Schema(implementation = SessionResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = "Entrée invalide",
            content =
                    @Content(schema = @Schema(implementation = AuthenticationErrorResponse.class)))
    @ApiResponse(
            responseCode = "401",
            description = "Identifiant ou mot de passe incorrect",
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
                    "Quota dépassé ou délai après des échecs répétés sur cet identifiant :"
                            + " l'en-tête Retry-After indique l'attente en secondes",
            content =
                    @Content(schema = @Schema(implementation = AuthenticationErrorResponse.class)))
    @ApiResponse(
            responseCode = "503",
            description =
                    "Compteurs, base de données ou plafond de calculs indisponibles :"
                            + " Retry-After est présent pour les compteurs et le plafond, absent"
                            + " pour la base",
            content =
                    @Content(schema = @Schema(implementation = AuthenticationErrorResponse.class)))
    @io.swagger.v3.oas.annotations.Parameter(
            name = "X-CSRF-TOKEN",
            in = io.swagger.v3.oas.annotations.enums.ParameterIn.HEADER,
            required = true,
            schema = @Schema(type = "string"))
    public SessionResponse login(
            @Valid @RequestBody LoginRequest body,
            HttpServletRequest request,
            HttpServletResponse response) {
        var grant =
                logIn.logIn(
                        body.identifier(),
                        body.password(),
                        request.getRemoteAddr(),
                        SessionPersistence.chosen(body.remembered()),
                        browser.reconnection(request),
                        browser.family(request));
        browser.establish(grant, true, request, response);
        return SessionResponse.of(grant.account());
    }

    @PostMapping("/resume")
    @Operation(
            summary = "Recréer une session depuis une reconnexion valide et renouveler son secret")
    @ApiResponse(
            responseCode = "200",
            description = "Session recréée",
            content = @Content(schema = @Schema(implementation = SessionResponse.class)))
    @ApiResponse(
            responseCode = "401",
            description =
                    "Reconnexion refusée (RECONNECTION_REFUSED) : cookie absent, illisible,"
                            + " inconnu, expiré, révoqué ou rejoué après la tolérance",
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
                    "Trop de reprises de cette reconnexion : la reconnexion reste valide et"
                            + " l'en-tête Retry-After indique l'attente en secondes",
            content =
                    @Content(schema = @Schema(implementation = AuthenticationErrorResponse.class)))
    @ApiResponse(
            responseCode = "503",
            description =
                    "Base de données indisponible, sans Retry-After. Les compteurs ne refusent"
                            + " la reprise (avec Retry-After) que réglés en mode de panne fermé :"
                            + " par défaut elle s'exécute sans eux",
            content =
                    @Content(schema = @Schema(implementation = AuthenticationErrorResponse.class)))
    @io.swagger.v3.oas.annotations.Parameter(
            name = "X-CSRF-TOKEN",
            in = io.swagger.v3.oas.annotations.enums.ParameterIn.HEADER,
            required = true,
            schema = @Schema(type = "string"))
    public SessionResponse resume(HttpServletRequest request, HttpServletResponse response) {
        String cookie = browser.reconnection(request);
        if (cookie == null) {
            browser.clearReconnectable(response);
            throw AuthenticationFailedException.reconnectionRefused();
        }
        AuthenticationGrant grant;
        try {
            grant = resumeSession.resume(cookie);
        } catch (AuthenticationFailedException error) {
            // Only a definitive refusal clears the indicator, not a quota or an outage.
            if (error.reason() == AuthenticationFailedException.Reason.RECONNECTION_REFUSED)
                browser.clearReconnectable(response);
            throw error;
        }
        browser.establish(grant, false, request, response);
        return SessionResponse.of(grant.account());
    }

    @GetMapping("/session")
    @Operation(summary = "Lire le compte de la session jeune vérifiée")
    @ApiResponse(
            responseCode = "200",
            description = "Session vérifiée",
            content = @Content(schema = @Schema(implementation = SessionResponse.class)))
    @ApiResponse(
            responseCode = "401",
            description =
                    "Aucune session valide, ou compte ou reconnexion révoqués depuis le filtre de"
                            + " session (UNAUTHENTICATED dans les deux cas)",
            content =
                    @Content(schema = @Schema(implementation = AuthenticationErrorResponse.class)))
    @ApiResponse(
            responseCode = "503",
            description = "Base de données indisponible pour vérifier la session",
            content =
                    @Content(schema = @Schema(implementation = AuthenticationErrorResponse.class)))
    public SessionResponse session(HttpServletRequest request) {
        var identity =
                (SessionIdentity) request.getSession(false).getAttribute(SessionIdentity.ATTRIBUTE);
        var account =
                getSession.get(
                        identity.accountId(), identity.credentialVersion(), identity.familyId());
        return SessionResponse.of(account);
    }
}
