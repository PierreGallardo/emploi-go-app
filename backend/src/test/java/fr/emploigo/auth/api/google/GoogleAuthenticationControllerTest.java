package fr.emploigo.auth.api.google;

import static org.junit.jupiter.params.provider.Arguments.argumentSet;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import fr.emploigo.auth.api.AuthenticationExceptionHandler;
import fr.emploigo.auth.api.session.BrowserAuthentication;
import fr.emploigo.auth.application.google.ConfirmGoogleEmailUseCase;
import fr.emploigo.auth.application.google.GoogleSettings;
import fr.emploigo.auth.application.google.HandleGoogleCallbackUseCase;
import fr.emploigo.auth.application.google.LinkGoogleAccountUseCase;
import fr.emploigo.auth.application.google.ReadGoogleStageUseCase;
import fr.emploigo.auth.application.google.SendGoogleEmailLinkUseCase;
import fr.emploigo.auth.application.google.SignUpWithGoogleUseCase;
import fr.emploigo.auth.application.google.StartGoogleLoginResult;
import fr.emploigo.auth.application.google.StartGoogleLoginUseCase;
import fr.emploigo.auth.application.google.VerifyLocalAccountForGoogleUseCase;
import fr.emploigo.auth.application.session.AuthenticationCookies;
import fr.emploigo.auth.application.session.AuthenticationSessions;
import fr.emploigo.auth.infrastructure.security.AuthenticationSecurityConfiguration;
import fr.emploigo.config.ClockConfiguration;
import fr.emploigo.config.SecurityConfiguration;
import fr.emploigo.support.CsrfTokens;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.CannotCreateTransactionException;

import java.util.stream.Stream;

@WebMvcTest(
        controllers = GoogleAuthenticationController.class,
        properties =
                "spring.autoconfigure.exclude=org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration")
@Import({
    SecurityConfiguration.class,
    ClockConfiguration.class,
    AuthenticationSecurityConfiguration.class,
    BrowserAuthentication.class,
    AuthenticationCookies.class,
    AuthenticationExceptionHandler.class
})
@ActiveProfiles("test")
class GoogleAuthenticationControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean GoogleSettings settings;
    @MockitoBean StartGoogleLoginUseCase startGoogleLogin;
    @MockitoBean HandleGoogleCallbackUseCase handleGoogleCallback;
    @MockitoBean ReadGoogleStageUseCase readGoogleStage;
    @MockitoBean VerifyLocalAccountForGoogleUseCase verifyLocalAccount;
    @MockitoBean SignUpWithGoogleUseCase signUpWithGoogle;
    @MockitoBean LinkGoogleAccountUseCase linkGoogleAccount;
    @MockitoBean SendGoogleEmailLinkUseCase sendEmailLink;
    @MockitoBean ConfirmGoogleEmailUseCase confirmEmail;
    @MockitoBean AuthenticationSessions sessions;

    private void verifyNoGoogleUseCase() {
        verifyNoInteractions(
                startGoogleLogin,
                handleGoogleCallback,
                readGoogleStage,
                verifyLocalAccount,
                signUpWithGoogle,
                linkGoogleAccount,
                sendEmailLink,
                confirmEmail);
    }

    static Stream<Arguments> mutations() {
        return Stream.of(
                argumentSet("départ", "start"),
                argumentSet("accès", "access"),
                argumentSet("inscription", "signup"),
                argumentSet("association", "link"),
                argumentSet("email", "email"),
                argumentSet("confirmation d'email", "email/confirm"));
    }

    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("mutations")
    void mutationWithoutCsrfTokenIsRefusedAsCsrfInvalid(String action) throws Exception {
        mvc.perform(post("/api/auth/google/" + action))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_INVALID"));

        verifyNoGoogleUseCase();
        verifyNoInteractions(sessions);
    }

    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("mutations")
    void mutationWithForgedCsrfTokenIsRefused(String action) throws Exception {
        mvc.perform(post("/api/auth/google/" + action).with(CsrfTokens.forged()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_INVALID"));

        verifyNoGoogleUseCase();
        verifyNoInteractions(sessions);
    }

    @Test
    void getOnStartIsRefused() throws Exception {
        mvc.perform(get("/api/auth/google/start")).andExpect(status().isUnauthorized());

        verifyNoGoogleUseCase();
    }

    @Test
    void signupWithoutTheProofOfAFlowIsRefusedAsInvalidFlow() throws Exception {
        mvc.perform(
                        post("/api/auth/google/signup")
                                .with(CsrfTokens.valid())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"flowId\":\"f496c1d5-03e2-4a40-a887-115580f9113e\",\"username\":\"fixture\",\"acceptedTerms\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_FLOW"));

        verifyNoGoogleUseCase();
    }

    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("refusedTerms")
    void signupWithoutTheTermsAcceptedIsRefusedAsInvalidInput(String terms) throws Exception {
        mvc.perform(
                        post("/api/auth/google/signup")
                                .with(CsrfTokens.valid())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"flowId\":\"f496c1d5-03e2-4a40-a887-115580f9113e\",\"username\":\"fixture\""
                                                + terms
                                                + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));

        verifyNoGoogleUseCase();
    }

    static Stream<Arguments> refusedTerms() {
        return Stream.of(
                argumentSet("case absente", ""),
                argumentSet("case non cochée", ",\"acceptedTerms\":false"));
    }

    @Test
    void callbackWhoseTransactionCannotStartRedirectsWithUnavailableInsteadOfAnswering503()
            throws Exception {
        var original =
                OAuth2AuthorizationRequest.authorizationCode()
                        .authorizationUri("https://accounts.google.com/o/oauth2/v2/auth")
                        .clientId("fixture")
                        .state("state-fixture")
                        .redirectUri("http://localhost/callback")
                        .build();
        when(sessions.browserProof(any())).thenReturn("proof-fixture");
        when(startGoogleLogin.start(eq("proof-fixture"), any(), any()))
                .thenReturn(new StartGoogleLoginResult(original, "proof-fixture"));
        when(settings.origin()).thenReturn("http://localhost:5173");
        when(handleGoogleCallback.handle(
                        any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new CannotCreateTransactionException("fixture"));
        var session = new MockHttpSession();
        mvc.perform(post("/api/auth/google/start").with(CsrfTokens.valid()).session(session))
                .andExpect(status().isOk());

        mvc.perform(
                        get("/api/auth/google/callback/google")
                                .session(session)
                                .param("state", "state-fixture")
                                .param("code", "code-fixture"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("http://localhost:5173/connexion-google"));

        mvc.perform(get("/api/auth/google/status").session(session))
                .andExpect(jsonPath("$.errorCode").value("UNAVAILABLE"));
    }
}
