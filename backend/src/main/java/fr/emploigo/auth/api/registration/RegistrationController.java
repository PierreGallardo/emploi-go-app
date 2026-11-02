package fr.emploigo.auth.api.registration;

import fr.emploigo.auth.api.AuthenticationErrorResponse;
import fr.emploigo.auth.api.session.BrowserAuthentication;
import fr.emploigo.auth.api.session.SessionResponse;
import fr.emploigo.auth.application.registration.ChooseUsernameUseCase;
import fr.emploigo.auth.application.registration.ConfirmRegistrationUseCase;
import fr.emploigo.auth.application.registration.RegistrationFailedException;
import fr.emploigo.auth.application.registration.ResendRegistrationCodeUseCase;
import fr.emploigo.auth.application.registration.StartRegistrationUseCase;
import fr.emploigo.auth.application.session.SessionPersistence;
import fr.emploigo.auth.domain.AuthenticationSecrets;
import fr.emploigo.auth.domain.RegistrationPage;
import fr.emploigo.auth.domain.SessionIdentity;
import fr.emploigo.config.ApiOnly;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
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

import java.time.Clock;

@RestController
@ApiOnly
@RequestMapping("/api/auth")
public class RegistrationController {
    private final StartRegistrationUseCase startRegistration;
    private final ResendRegistrationCodeUseCase resendRegistrationCode;
    private final ConfirmRegistrationUseCase confirmRegistration;
    private final ChooseUsernameUseCase chooseUsername;
    private final BrowserAuthentication browser;
    /** The identifier of the registration, which the tab that asked for it sends back. */
    static final String REGISTRATION_HEADER = "Registration-Id";

    private final Clock clock;

    public RegistrationController(
            StartRegistrationUseCase startRegistration,
            ResendRegistrationCodeUseCase resendRegistrationCode,
            ConfirmRegistrationUseCase confirmRegistration,
            ChooseUsernameUseCase chooseUsername,
            BrowserAuthentication browser,
            Clock clock) {
        this.startRegistration = startRegistration;
        this.resendRegistrationCode = resendRegistrationCode;
        this.confirmRegistration = confirmRegistration;
        this.chooseUsername = chooseUsername;
        this.browser = browser;
        this.clock = clock;
    }

    @PostMapping("/signup")
    @Parameter(
            name = "X-CSRF-TOKEN",
            in = ParameterIn.HEADER,
            required = true,
            schema = @Schema(type = "string"))
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(
            summary =
                    "Demander une inscription locale et transmettre un courriel directement au"
                            + " fournisseur, sans connecter ; la session de ce navigateur garde"
                            + " l'inscription, que seul ce navigateur peut confirmer")
    @ApiResponse(
            responseCode = "202",
            description =
                    "Courriel accepté par le fournisseur ; ne révèle pas l'existence de"
                            + " l'email ni la livraison finale",
            headers = {
                @Header(
                        name = "Retry-After",
                        description = "Attente avant un nouvel email à cette adresse, en secondes",
                        required = true,
                        schema = @Schema(type = "integer", format = "int64", minimum = "1")),
                @Header(
                        name = REGISTRATION_HEADER,
                        description =
                                "Identifiant de l'inscription, à renvoyer avec le renvoi et la"
                                        + " confirmation depuis la même page",
                        required = true,
                        schema = @Schema(type = "string"))
            },
            content = @Content)
    @ApiResponse(
            responseCode = "400",
            description =
                    "Entrée invalide : champs ou confirmation différente du mot de passe"
                            + " (INVALID_INPUT), ou mot de passe refusé (WEAK_PASSWORD)",
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
                    "Quota dépassé (RATE_LIMITED), ou un email vient d'être envoyé à cette"
                            + " adresse (MAIL_RECENTLY_SENT) : l'en-tête Retry-After indique"
                            + " l'attente en secondes ; aucune inscription n'est gardée",
            content =
                    @Content(schema = @Schema(implementation = AuthenticationErrorResponse.class)))
    @ApiResponse(
            responseCode = "503",
            description =
                    "Inscription indisponible (courriel désactivé ou non envoyé, base"
                            + " de données, compteurs) ou plafond de calculs atteint (BUSY) :"
                            + " Retry-After est présent pour les compteurs et le plafond, absent"
                            + " pour le reste",
            content =
                    @Content(schema = @Schema(implementation = AuthenticationErrorResponse.class)))
    public void signup(
            @Valid @RequestBody RegistrationRequest body,
            HttpServletRequest request,
            HttpServletResponse response) {
        // A new key at every request, even in the same browser: see RegistrationPage.
        String key = AuthenticationSecrets.generate();
        var wait =
                startRegistration.start(
                        body.email(),
                        body.password(),
                        body.confirmation(),
                        key,
                        request.getRemoteAddr());
        var page = RegistrationPage.issued(body.email(), key, body.rememberMe(), clock.instant());
        request.getSession(true).setAttribute(RegistrationPage.ATTRIBUTE, page);
        response.setHeader(REGISTRATION_HEADER, page.id());
        response.setHeader("Retry-After", Long.toString(wait.toSeconds()));
    }

    @PostMapping("/signup/resend")
    @Parameter(
            name = "X-CSRF-TOKEN",
            in = ParameterIn.HEADER,
            required = true,
            schema = @Schema(type = "string"))
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Demander un nouveau code, sans révéler si une inscription est en attente")
    @ApiResponse(
            responseCode = "202",
            description =
                    "Courriel accepté par le fournisseur ; ancien code invalidé si un"
                            + " nouveau code est créé",
            headers =
                    @Header(
                            name = "Retry-After",
                            description = "Attente avant un nouvel email à cette adresse, en secondes",
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
            responseCode = "410",
            description =
                    "Aucune inscription en cours pour cette page dans ce navigateur"
                            + " (NO_PENDING_REGISTRATION)",
            content =
                    @Content(schema = @Schema(implementation = AuthenticationErrorResponse.class)))
    @ApiResponse(
            responseCode = "429",
            description =
                    "Quota dépassé (RATE_LIMITED), ou un email vient d'être envoyé à cette"
                            + " adresse (MAIL_RECENTLY_SENT), ou plus aucun code pendant une heure"
                            + " après des essais ratés (CODES_BLOCKED) : l'en-tête Retry-After"
                            + " indique l'attente en secondes",
            content =
                    @Content(schema = @Schema(implementation = AuthenticationErrorResponse.class)))
    @ApiResponse(
            responseCode = "503",
            description =
                    "Inscription indisponible (courriel désactivé ou non envoyé, base"
                            + " de données, compteurs) : Retry-After est présent pour les"
                            + " compteurs, absent pour le reste",
            content =
                    @Content(schema = @Schema(implementation = AuthenticationErrorResponse.class)))
    public void resend(
            @Valid @RequestBody ResendConfirmationRequest body,
            HttpServletRequest request,
            HttpServletResponse response) {
        var page = pending(request, body.registration());
        var wait =
                resendRegistrationCode.resend(
                        page.emailKey(), page.key(), request.getRemoteAddr());
        // Renewed whether a code was issued or not, in the session found: a confirmation may have
        // replaced it meanwhile, and no other session is created for it.
        var session = request.getSession(false);
        if (session != null)
            session.setAttribute(RegistrationPage.ATTRIBUTE, page.renewed(clock.instant()));
        response.setHeader("Retry-After", Long.toString(wait.toSeconds()));
    }

    @PostMapping("/signup/confirm")
    @Parameter(
            name = "X-CSRF-TOKEN",
            in = ParameterIn.HEADER,
            required = true,
            schema = @Schema(type = "string"))
    @Operation(
            summary =
                    "Confirmer l'inscription de ce navigateur avec le code reçu par email et"
                            + " ouvrir la session du compte, qui choisit ensuite son pseudo")
    @ApiResponse(
            responseCode = "200",
            description = "Inscription confirmée, compte connecté",
            content = @Content(schema = @Schema(implementation = SessionResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = "Entrée invalide (INVALID_INPUT) ou code erroné (INVALID_CODE)",
            content =
                    @Content(schema = @Schema(implementation = AuthenticationErrorResponse.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Jeton CSRF invalide",
            content =
                    @Content(schema = @Schema(implementation = AuthenticationErrorResponse.class)))
    @ApiResponse(
            responseCode = "409",
            description =
                    "Compte déjà existant pour cet email (ACCOUNT_ALREADY_EXISTS)",
            content =
                    @Content(schema = @Schema(implementation = AuthenticationErrorResponse.class)))
    @ApiResponse(
            responseCode = "410",
            description =
                    "Code expiré, remplacé, utilisé ou épuisé par trop d'essais ; ou aucune"
                            + " inscription en cours pour cette page dans ce navigateur"
                            + " (NO_PENDING_REGISTRATION)",
            content =
                    @Content(schema = @Schema(implementation = AuthenticationErrorResponse.class)))
    @ApiResponse(
            responseCode = "429",
            description =
                    "Quota dépassé, ou plus aucun code pendant une heure après 15"
                            + " essais ratés (CODES_BLOCKED) : l'en-tête Retry-After indique"
                            + " l'attente en secondes",
            content =
                    @Content(schema = @Schema(implementation = AuthenticationErrorResponse.class)))
    @ApiResponse(
            responseCode = "503",
            description =
                    "Base de données ou compteurs indisponibles : Retry-After est"
                            + " présent pour les compteurs, absent pour la base",
            content =
                    @Content(schema = @Schema(implementation = AuthenticationErrorResponse.class)))
    public SessionResponse confirm(
            @Valid @RequestBody ConfirmationRequest body,
            HttpServletRequest request,
            HttpServletResponse response) {
        var page = pending(request, body.registration());
        var grant =
                confirmRegistration.confirm(
                        page.emailKey(),
                        body.code(),
                        page.key(),
                        SessionPersistence.chosen(page.remembered()),
                        browser.reconnection(request),
                        browser.family(request),
                        request.getRemoteAddr());
        browser.establish(grant, false, request, response);
        return SessionResponse.of(grant.account());
    }

    @PostMapping("/username")
    @Parameter(
            name = "X-CSRF-TOKEN",
            in = ParameterIn.HEADER,
            required = true,
            schema = @Schema(type = "string"))
    @Operation(
            summary =
                    "Choisir le pseudo du compte de la session, qui n'en a pas encore, et rouvrir"
                            + " la session, gardée par le navigateur comme celle qu'elle remplace")
    @ApiResponse(
            responseCode = "200",
            description = "Pseudo enregistré, session rouverte",
            content = @Content(schema = @Schema(implementation = SessionResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = "Entrée invalide (INVALID_INPUT)",
            content =
                    @Content(schema = @Schema(implementation = AuthenticationErrorResponse.class)))
    @ApiResponse(
            responseCode = "401",
            description = "Aucune session valide (UNAUTHENTICATED)",
            content =
                    @Content(schema = @Schema(implementation = AuthenticationErrorResponse.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Jeton CSRF invalide",
            content =
                    @Content(schema = @Schema(implementation = AuthenticationErrorResponse.class)))
    @ApiResponse(
            responseCode = "409",
            description =
                    "Pseudo pris par un autre compte (USERNAME_UNAVAILABLE), saisie à garder ;"
                            + " ou pseudo du compte déjà choisi (USERNAME_ALREADY_CHOSEN)",
            content =
                    @Content(schema = @Schema(implementation = AuthenticationErrorResponse.class)))
    @ApiResponse(
            responseCode = "422",
            description =
                    "Pseudo hors règle : 2 à 32 lettres sans accent, chiffres, _ ou ., sans deux"
                            + " points de suite (INVALID_USERNAME)",
            content =
                    @Content(schema = @Schema(implementation = AuthenticationErrorResponse.class)))
    @ApiResponse(
            responseCode = "503",
            description = "Base de données indisponible",
            content =
                    @Content(schema = @Schema(implementation = AuthenticationErrorResponse.class)))
    public SessionResponse username(
            @Valid @RequestBody UsernameRequest body,
            HttpServletRequest request,
            HttpServletResponse response) {
        var identity =
                (SessionIdentity) request.getSession(false).getAttribute(SessionIdentity.ATTRIBUTE);
        var grant =
                chooseUsername.choose(
                        identity.accountId(),
                        identity.credentialVersion(),
                        identity.familyId(),
                        body.username(),
                        browser.reconnection(request));
        browser.establish(grant, identity.explicit(), request, response);
        return SessionResponse.of(grant.account());
    }

    /** The registration this browser asked for, if the tab holds it and it lives. */
    private RegistrationPage pending(HttpServletRequest request, String registration) {
        var session = request.getSession(false);
        if (session != null
                && session.getAttribute(RegistrationPage.ATTRIBUTE)
                        instanceof RegistrationPage page
                && page.heldBy(registration, clock.instant())) return page;
        throw new RegistrationFailedException(
                RegistrationFailedException.Reason.NO_PENDING_REGISTRATION);
    }
}
