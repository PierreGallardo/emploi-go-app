package fr.emploigo.auth.api.google;

import fr.emploigo.auth.api.AuthenticationErrorResponse;
import fr.emploigo.auth.api.session.BrowserAuthentication;
import fr.emploigo.auth.api.session.SessionResponse;
import fr.emploigo.auth.application.failure.AuthenticationFailedException;
import fr.emploigo.auth.application.google.ConfirmGoogleEmailUseCase;
import fr.emploigo.auth.application.google.GoogleAuthenticationFailedException;
import fr.emploigo.auth.application.google.GoogleSettings;
import fr.emploigo.auth.application.google.HandleGoogleCallbackUseCase;
import fr.emploigo.auth.application.google.LinkGoogleAccountUseCase;
import fr.emploigo.auth.application.google.ReadGoogleStageUseCase;
import fr.emploigo.auth.application.google.SendGoogleEmailLinkUseCase;
import fr.emploigo.auth.application.google.SignUpWithGoogleUseCase;
import fr.emploigo.auth.application.google.StartGoogleLoginUseCase;
import fr.emploigo.auth.application.google.VerifyLocalAccountForGoogleUseCase;
import fr.emploigo.auth.domain.SessionIdentity;
import fr.emploigo.config.ApiOnly;

import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.Serializable;
import java.util.UUID;

@RestController
@ApiOnly
@RequestMapping("/api/auth/google")
public class GoogleAuthenticationController {
    private static final String FLOW = "auth.google.flow";
    private static final String PENDING = "auth.google.pending";
    private static final String ERROR = "auth.google.error";
    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger(GoogleAuthenticationController.class);
    private final GoogleSettings settings;
    private final StartGoogleLoginUseCase startGoogleLogin;
    private final HandleGoogleCallbackUseCase handleGoogleCallback;
    private final ReadGoogleStageUseCase readGoogleStage;
    private final VerifyLocalAccountForGoogleUseCase verifyLocalAccount;
    private final SignUpWithGoogleUseCase signUpWithGoogle;
    private final LinkGoogleAccountUseCase linkGoogleAccount;
    private final SendGoogleEmailLinkUseCase sendEmailLink;
    private final ConfirmGoogleEmailUseCase confirmEmail;
    private final BrowserAuthentication browser;

    public GoogleAuthenticationController(
            GoogleSettings settings,
            StartGoogleLoginUseCase startGoogleLogin,
            HandleGoogleCallbackUseCase handleGoogleCallback,
            ReadGoogleStageUseCase readGoogleStage,
            VerifyLocalAccountForGoogleUseCase verifyLocalAccount,
            SignUpWithGoogleUseCase signUpWithGoogle,
            LinkGoogleAccountUseCase linkGoogleAccount,
            SendGoogleEmailLinkUseCase sendEmailLink,
            ConfirmGoogleEmailUseCase confirmEmail,
            BrowserAuthentication browser) {
        this.settings = settings;
        this.startGoogleLogin = startGoogleLogin;
        this.handleGoogleCallback = handleGoogleCallback;
        this.readGoogleStage = readGoogleStage;
        this.verifyLocalAccount = verifyLocalAccount;
        this.signUpWithGoogle = signUpWithGoogle;
        this.linkGoogleAccount = linkGoogleAccount;
        this.sendEmailLink = sendEmailLink;
        this.confirmEmail = confirmEmail;
        this.browser = browser;
    }

    private record Flow(OAuth2AuthorizationRequest original, String proof)
            implements Serializable {
        @Override
        public String toString() {
            return "Flow[redacted]";
        }
    }

    private record Pending(UUID id, String proof) implements Serializable {
        @Override
        public String toString() {
            return "Pending[redacted]";
        }
    }

    @PostMapping("/start")
    @Operation(summary = "Commencer Google avec une preuve navigateur et CSRF")
    @ApiResponse(
            responseCode = "200",
            description = "Autorisation Google à ouvrir",
            content = @Content(schema = @Schema(implementation = GoogleStartedResponse.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Jeton CSRF invalide",
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
                    "Parcours Google désactivé, base de données ou compteurs"
                            + " indisponibles : Retry-After est présent pour les compteurs, absent"
                            + " pour le reste",
            content =
                    @Content(schema = @Schema(implementation = AuthenticationErrorResponse.class)))
    @Parameter(
            name = "X-CSRF-TOKEN",
            in = ParameterIn.HEADER,
            required = true,
            schema = @Schema(type = "string"))
    public GoogleStartedResponse start(HttpServletRequest request) {
        // The use case runs before the session is created: a refused start writes no session row.
        var existing = request.getSession(false);
        var started =
                startGoogleLogin.start(
                        existing != null ? browser.browserProof(existing) : null,
                        identity(request),
                        request.getRemoteAddr());
        var session = existing != null ? existing : request.getSession(true);
        session.setAttribute(BrowserAuthentication.BROWSER_PROOF, started.proof());
        session.setAttribute(FLOW, new Flow(started.request(), started.proof()));
        session.removeAttribute(PENDING);
        session.removeAttribute(ERROR);
        return new GoogleStartedResponse(started.request().getAuthorizationRequestUri());
    }

    @Hidden
    @GetMapping("/callback/google")
    public void callback(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Referrer-Policy", "no-referrer");
        try {
            var session = request.getSession(false);
            var flow =
                    session != null && session.getAttribute(FLOW) instanceof Flow value
                            ? value
                            : null;
            var result =
                    handleGoogleCallback.handle(
                            flow != null ? flow.original() : null,
                            flow != null ? flow.proof() : null,
                            identity(request),
                            single(request, "code"),
                            single(request, "state"),
                            single(request, "error"),
                            browser.reconnection(request),
                            browser.family(request),
                            request.getRemoteAddr());
            if (result.grant() != null) {
                browser.establish(result.grant(), true, request, response, true);
                request.getSession().removeAttribute(PENDING);
                request.getSession().removeAttribute(ERROR);
                response.sendRedirect(settings.origin() + "/");
                return;
            }
            browser.rotateBrowserProof(request, response);
            request.getSession()
                    .setAttribute(
                            PENDING,
                            new Pending(result.pendingId(), result.pendingProof()));
            request.getSession().removeAttribute(ERROR);
        } catch (GoogleAuthenticationFailedException error) {
            refused(request, error.reason().name());
        } catch (AuthenticationFailedException error) {
            refused(request, error.reason().name());
        } catch (DataAccessException | TransactionException error) {
            LOG.warn("Retour Google indisponible ({})", error.getClass().getSimpleName());
            refused(request, "UNAVAILABLE");
        }
        response.sendRedirect(settings.origin() + "/connexion-google");
    }

    /**
     * Remembers the failure for {@code /status}, only in the session of a browser that started a
     * flow: never creates one.
     */
    private static void refused(HttpServletRequest request, String reason) {
        var session = request.getSession(false);
        if (session != null) session.setAttribute(ERROR, reason);
    }

    @GetMapping("/status")
    @Operation(summary = "Lire l'étape Google de ce navigateur, sans token fournisseur")
    @ApiResponse(
            responseCode = "200",
            description = "Étape de ce navigateur",
            content = @Content(schema = @Schema(implementation = GoogleStatusResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description =
                    "Le parcours en attente de ce navigateur n'est plus valable"
                            + " (INVALID_FLOW)",
            content =
                    @Content(schema = @Schema(implementation = AuthenticationErrorResponse.class)))
    @ApiResponse(
            responseCode = "410",
            description = "Le parcours en attente de ce navigateur a expiré (EXPIRED_FLOW)",
            content =
                    @Content(schema = @Schema(implementation = AuthenticationErrorResponse.class)))
    @ApiResponse(
            responseCode = "503",
            description = "Base de données indisponible : Retry-After est absent",
            content =
                    @Content(schema = @Schema(implementation = AuthenticationErrorResponse.class)))
    public GoogleStatusResponse status(HttpServletRequest request) {
        var session = request.getSession(false);
        String error =
                session != null && session.getAttribute(ERROR) instanceof String value
                        ? value
                        : null;
        var pending =
                session != null && session.getAttribute(PENDING) instanceof Pending value
                        ? value
                        : null;
        return pending == null
                ? new GoogleStatusResponse(false, null, null, error)
                : new GoogleStatusResponse(
                        true,
                        pending.id(),
                        readGoogleStage.read(pending.id(), pending.proof(), identity(request)),
                        error);
    }

    @PostMapping("/access")
    @Operation(summary = "Vérifier explicitement le compte local avant confirmation de liaison")
    @ApiResponse(
            responseCode = "200",
            description = "Compte local vérifié",
            content = @Content(schema = @Schema(implementation = SessionResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description =
                    "Entrée invalide, parcours absent ou refusé, ou email Google à"
                            + " confirmer d'abord",
            content =
                    @Content(schema = @Schema(implementation = AuthenticationErrorResponse.class)))
    @ApiResponse(
            responseCode = "401",
            description =
                    "Identifiant ou mot de passe incorrect, ou compte qui ne correspond"
                            + " pas à l'adresse Google (INVALID_CREDENTIALS)",
            content =
                    @Content(schema = @Schema(implementation = AuthenticationErrorResponse.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Jeton CSRF invalide",
            content =
                    @Content(schema = @Schema(implementation = AuthenticationErrorResponse.class)))
    @ApiResponse(
            responseCode = "409",
            description = "Ce compte est déjà associé à un compte Google (ALREADY_LINKED)",
            content =
                    @Content(schema = @Schema(implementation = AuthenticationErrorResponse.class)))
    @ApiResponse(
            responseCode = "410",
            description = "Parcours expiré (EXPIRED_FLOW)",
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
                    "Parcours Google désactivé, base de données, compteurs ou plafond"
                            + " de calculs indisponibles (BUSY) : Retry-After est présent pour les"
                            + " compteurs et le plafond, absent pour le reste",
            content =
                    @Content(schema = @Schema(implementation = AuthenticationErrorResponse.class)))
    @Parameter(
            name = "X-CSRF-TOKEN",
            in = ParameterIn.HEADER,
            required = true,
            schema = @Schema(type = "string"))
    public SessionResponse access(
            @Valid @RequestBody GoogleAccessRequest body,
            HttpServletRequest request,
            HttpServletResponse response) {
        var pending = pending(request, body.flowId());
        var grant =
                verifyLocalAccount.verify(
                        pending.id(),
                        pending.proof(),
                        identity(request),
                        body.identifier(),
                        body.password(),
                        browser.reconnection(request),
                        browser.family(request),
                        request.getRemoteAddr());
        browser.establish(grant, true, request, response, true);
        return SessionResponse.of(grant.account());
    }

    @PostMapping("/signup")
    @Operation(summary = "Créer le compte Google avec un pseudo choisi, sans mot de passe local")
    @ApiResponse(
            responseCode = "200",
            description = "Compte créé et connecté",
            content = @Content(schema = @Schema(implementation = SessionResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description =
                    "Entrée invalide, parcours absent ou refusé, ou email Google à"
                            + " confirmer d'abord",
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
                    "Pseudo indisponible, compte existant pour cet email ou compte"
                            + " Google déjà associé",
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
            responseCode = "410",
            description = "Parcours expiré (EXPIRED_FLOW)",
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
                    "Parcours Google désactivé, base de données ou compteurs"
                            + " indisponibles : Retry-After est présent pour les compteurs, absent"
                            + " pour le reste",
            content =
                    @Content(schema = @Schema(implementation = AuthenticationErrorResponse.class)))
    @Parameter(
            name = "X-CSRF-TOKEN",
            in = ParameterIn.HEADER,
            required = true,
            schema = @Schema(type = "string"))
    public SessionResponse signup(
            @Valid @RequestBody GoogleSignupRequest body,
            HttpServletRequest request,
            HttpServletResponse response) {
        var pending = pending(request, body.flowId());
        var grant =
                signUpWithGoogle.signUp(
                        pending.id(),
                        pending.proof(),
                        identity(request),
                        body.username(),
                        browser.reconnection(request),
                        browser.family(request),
                        request.getRemoteAddr());
        browser.establish(grant, true, request, response, true);
        request.getSession().removeAttribute(PENDING);
        return SessionResponse.of(grant.account());
    }

    @PostMapping("/link")
    @Operation(
            summary =
                    "Confirmer l'association en conservant les sessions et envoyer la notification")
    @ApiResponse(
            responseCode = "200",
            description = "Association durable ; la notification part une fois, sans reprise",
            content = @Content(schema = @Schema(implementation = GoogleLinkResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description =
                    "Entrée invalide, parcours absent ou refusé, ou email Google à"
                            + " confirmer d'abord",
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
                    "Accès au compte local à vérifier de nouveau"
                            + " (ACCOUNT_ACCESS_REQUIRED) ou association déjà faite"
                            + " (ALREADY_LINKED)",
            content =
                    @Content(schema = @Schema(implementation = AuthenticationErrorResponse.class)))
    @ApiResponse(
            responseCode = "410",
            description = "Parcours expiré (EXPIRED_FLOW)",
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
                    "Parcours Google désactivé, base de données ou compteurs"
                            + " indisponibles : Retry-After est présent pour les compteurs, absent"
                            + " pour le reste",
            content =
                    @Content(schema = @Schema(implementation = AuthenticationErrorResponse.class)))
    @Parameter(
            name = "X-CSRF-TOKEN",
            in = ParameterIn.HEADER,
            required = true,
            schema = @Schema(type = "string"))
    public GoogleLinkResponse link(
            @Valid @RequestBody GoogleLinkRequest body,
            HttpServletRequest request,
            HttpServletResponse response) {
        var pending = pending(request, body.flowId());
        linkGoogleAccount.link(
                pending.id(), pending.proof(), identity(request), request.getRemoteAddr());
        request.getSession().removeAttribute(PENDING);
        return new GoogleLinkResponse(true);
    }

    @PostMapping("/email")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Envoyer ou reprendre un lien local pour une adresse Google externe")
    @ApiResponse(responseCode = "202", description = "Lien envoyé", content = @Content)
    @ApiResponse(
            responseCode = "400",
            description = "Entrée invalide, ou parcours absent ou refusé",
            content =
                    @Content(schema = @Schema(implementation = AuthenticationErrorResponse.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Jeton CSRF invalide",
            content =
                    @Content(schema = @Schema(implementation = AuthenticationErrorResponse.class)))
    @ApiResponse(
            responseCode = "410",
            description = "Parcours expiré (EXPIRED_FLOW)",
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
                    "Parcours Google désactivé, courriel non envoyé, base de données ou"
                            + " compteurs indisponibles : Retry-After est présent pour les"
                            + " compteurs, absent pour le reste",
            content =
                    @Content(schema = @Schema(implementation = AuthenticationErrorResponse.class)))
    @Parameter(
            name = "X-CSRF-TOKEN",
            in = ParameterIn.HEADER,
            required = true,
            schema = @Schema(type = "string"))
    public void email(@Valid @RequestBody GoogleEmailRequest body, HttpServletRequest request) {
        var pending = pending(request, body.flowId());
        sendEmailLink.send(
                pending.id(), pending.proof(), identity(request), request.getRemoteAddr());
    }

    @PostMapping("/email/confirm")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Confirmer l'email Google sans créer de compte ou de connexion")
    @ApiResponse(
            responseCode = "204",
            description = "Email Google confirmé",
            content = @Content)
    @ApiResponse(
            responseCode = "400",
            description = "Entrée invalide ou lien invalide (INVALID_LINK)",
            content =
                    @Content(schema = @Schema(implementation = AuthenticationErrorResponse.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Jeton CSRF invalide",
            content =
                    @Content(schema = @Schema(implementation = AuthenticationErrorResponse.class)))
    @ApiResponse(
            responseCode = "410",
            description = "Lien expiré, remplacé ou déjà utilisé",
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
                    "Parcours Google désactivé, base de données ou compteurs"
                            + " indisponibles : Retry-After est présent pour les compteurs, absent"
                            + " pour le reste",
            content =
                    @Content(schema = @Schema(implementation = AuthenticationErrorResponse.class)))
    @Parameter(
            name = "X-CSRF-TOKEN",
            in = ParameterIn.HEADER,
            required = true,
            schema = @Schema(type = "string"))
    public void confirm(
            @Valid @RequestBody GoogleConfirmationRequest body, HttpServletRequest request) {
        confirmEmail.confirm(body.token(), request.getRemoteAddr());
    }

    private static Pending pending(HttpServletRequest request, UUID expected) {
        var session = request.getSession(false);
        if (session == null
                || !(session.getAttribute(PENDING) instanceof Pending value)
                || !value.id().equals(expected))
            throw new GoogleAuthenticationFailedException(GoogleAuthenticationFailedException.Reason.INVALID_FLOW);
        return value;
    }

    private static SessionIdentity identity(HttpServletRequest request) {
        var session = request.getSession(false);
        return session != null
                        && session.getAttribute(SessionIdentity.ATTRIBUTE)
                                instanceof SessionIdentity value
                ? value
                : null;
    }

    private static String single(HttpServletRequest request, String name) {
        String[] values = request.getParameterValues(name);
        if (values != null && (values.length != 1 || values[0].length() > 4096))
            throw new GoogleAuthenticationFailedException(GoogleAuthenticationFailedException.Reason.INVALID_FLOW);
        return values == null ? null : values[0];
    }
}
