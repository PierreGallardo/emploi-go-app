package fr.emploigo.auth.api.recovery;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import fr.emploigo.auth.api.AuthenticationExceptionHandler;
import fr.emploigo.auth.api.session.BrowserAuthentication;
import fr.emploigo.auth.application.recovery.RequestPasswordResetUseCase;
import fr.emploigo.auth.application.recovery.ResetPasswordUseCase;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.stream.Stream;

@WebMvcTest(
        controllers = PasswordRecoveryController.class,
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
class PasswordRecoveryControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean RequestPasswordResetUseCase requestReset;
    @MockitoBean ResetPasswordUseCase resetPassword;
    @MockitoBean AuthenticationSessions sessions;

    static Stream<Arguments> actions() {
        return Stream.of(argumentSet("demande", "forgot"), argumentSet("changement", "reset"));
    }

    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("actions")
    void getCannotChangeAnything(String action) throws Exception {
        mvc.perform(get("/api/auth/password/" + action)).andExpect(status().isUnauthorized());

        verifyNoInteractions(requestReset, resetPassword);
    }

    @Test
    void publicRequestAnswers202WithTheWaitAndWithoutAnyAccountInformation() throws Exception {
        when(requestReset.request(any(), any())).thenReturn(Duration.ofSeconds(60));

        mvc.perform(
                        post("/api/auth/password/forgot")
                                .with(CsrfTokens.valid())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"email\":\"fixture@example.invalid\"}"))
                .andExpect(status().isAccepted())
                .andExpect(content().string(""))
                .andExpect(header().string("Retry-After", "60"))
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    void publicRequestWithAnInvalidEmailAnswersInvalidInput() throws Exception {
        mvc.perform(
                        post("/api/auth/password/forgot")
                                .with(CsrfTokens.valid())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"email\":\"invalid\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    @Test
    void completedResetAnswersChangedAndClearsTheBrowserWithoutAuthenticating() throws Exception {
        String body =
                "{\"token\":\"fixture\",\"password\":\"Une phrase fictive 1\","
                        + "\"confirmation\":\"Une phrase fictive 1\"}";

        mvc.perform(
                        post("/api/auth/password/reset")
                                .with(CsrfTokens.valid())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changed").value(true))
                .andExpect(
                        header().stringValues(
                                        "Set-Cookie",
                                        hasItem(containsString("__Host-EMPLOIGO_RECONNECTION=;"))));

        verifyNoInteractions(sessions);
    }
}
