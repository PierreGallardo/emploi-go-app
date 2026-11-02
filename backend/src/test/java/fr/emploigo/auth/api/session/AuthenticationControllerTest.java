package fr.emploigo.auth.api.session;

import static fr.emploigo.support.HostCookies.RECONNECTABLE_COOKIE;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import fr.emploigo.auth.api.AuthenticationExceptionHandler;
import fr.emploigo.auth.application.session.AuthenticationCookies;
import fr.emploigo.auth.application.session.AuthenticationSessions;
import fr.emploigo.auth.application.session.GetSessionUseCase;
import fr.emploigo.auth.application.session.LogInWithPasswordUseCase;
import fr.emploigo.auth.application.session.ResumeSessionUseCase;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.stream.Stream;

@WebMvcTest(
        controllers = AuthenticationController.class,
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
class AuthenticationControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean LogInWithPasswordUseCase logIn;
    @MockitoBean GetSessionUseCase getSession;
    @MockitoBean AuthenticationSessions sessions;
    @MockitoBean ResumeSessionUseCase resumeSession;

    static Stream<Arguments> mutations() {
        return Stream.of(
                argumentSet("connexion", "login"),
                argumentSet("déconnexion", "logout"),
                argumentSet("reprise", "resume"));
    }

    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("mutations")
    void mutationWithoutCsrfTokenIsRefusedAsCsrfInvalid(String action) throws Exception {
        mvc.perform(post("/api/auth/" + action))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_INVALID"));

        verifyNoInteractions(logIn, getSession, sessions, resumeSession);
    }

    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("mutations")
    void mutationWithForgedCsrfTokenIsRefused(String action) throws Exception {
        mvc.perform(post("/api/auth/" + action).with(CsrfTokens.forged()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_INVALID"));

        verifyNoInteractions(logIn, getSession, sessions, resumeSession);
    }

    @Test
    void csrfCookieIsIssuedWithoutSession() throws Exception {
        var result =
                mvc.perform(get("/api/auth/csrf"))
                        .andExpect(status().isNoContent())
                        .andExpect(header().string("Cache-Control", "no-store"))
                        .andExpect(cookie().exists("__Host-XSRF-TOKEN"))
                        .andExpect(cookie().doesNotExist("__Host-EMPLOIGO_SESSION"))
                        .andReturn();

        assertThat(result.getRequest().getSession(false)).as("session").isNull();
    }

    @Test
    void sessionIsUnauthenticatedWithoutLogin() throws Exception {
        mvc.perform(get("/api/auth/session"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void resumeWithoutReconnectionIsRefusedAndClearsTheReconnectableIndicator() throws Exception {
        mvc.perform(post("/api/auth/resume").with(CsrfTokens.valid()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("RECONNECTION_REFUSED"))
                .andExpect(cookie().maxAge(RECONNECTABLE_COOKIE, 0));

        verifyNoInteractions(sessions, resumeSession);
    }

    @Test
    void loginWithAnEmptyIdentifierIsRefusedAsInvalidInput() throws Exception {
        mvc.perform(
                        post("/api/auth/login")
                                .with(CsrfTokens.valid())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"identifier\":\"\",\"password\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));

        verifyNoInteractions(logIn);
    }

    @Test
    void getOnLoginIsRefused() throws Exception {
        mvc.perform(get("/api/auth/login")).andExpect(status().isUnauthorized());

        verifyNoInteractions(logIn);
    }

    @Test
    void postOnSessionIsRefused() throws Exception {
        mvc.perform(post("/api/auth/session").with(CsrfTokens.valid()))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(logIn);
    }
}
