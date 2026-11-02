package fr.emploigo.auth.api.registration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import fr.emploigo.auth.api.AuthenticationExceptionHandler;
import fr.emploigo.auth.api.session.BrowserAuthentication;
import fr.emploigo.auth.application.failure.AuthenticationFailedException;
import fr.emploigo.auth.application.registration.ChooseUsernameUseCase;
import fr.emploigo.auth.application.registration.ConfirmRegistrationUseCase;
import fr.emploigo.auth.application.registration.RegistrationFailedException;
import fr.emploigo.auth.application.registration.ResendRegistrationCodeUseCase;
import fr.emploigo.auth.application.registration.StartRegistrationUseCase;
import fr.emploigo.auth.application.session.AuthenticatedAccount;
import fr.emploigo.auth.application.session.AuthenticationCookies;
import fr.emploigo.auth.application.session.AuthenticationGrant;
import fr.emploigo.auth.application.session.AuthenticationSessions;
import fr.emploigo.auth.application.session.SessionPersistence;
import fr.emploigo.auth.domain.RegistrationPage;
import fr.emploigo.auth.infrastructure.security.AuthenticationSecurityConfiguration;
import fr.emploigo.config.ClockConfiguration;
import fr.emploigo.config.SecurityConfiguration;
import fr.emploigo.support.CsrfTokens;
import fr.emploigo.support.HostCookies;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Stream;

@WebMvcTest(
        controllers = RegistrationController.class,
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
class RegistrationControllerTest {
    private static final String KEY = "k".repeat(43);
    private static final String ID = "f3c1d1a2-6a55-4d5e-9b0e-2c1f2d3e4f50";
    private static final String RESEND = "{\"registration\":\"" + ID + "\"}";
    private static final String EMAIL = "fixture@example.invalid";
    private static final String SIGNUP =
            "{\"email\":\"fixture@example.invalid\",\"password\":\"Une"
                    + " phrase fictive 1\",\"confirmation\":\"Une phrase fictive 1\","
                    + "\"acceptedTerms\":true,\"rememberMe\":true}";

    @Autowired MockMvc mvc;
    @MockitoBean StartRegistrationUseCase startRegistration;
    @MockitoBean ResendRegistrationCodeUseCase resendRegistrationCode;
    @MockitoBean ConfirmRegistrationUseCase confirmRegistration;
    @MockitoBean ChooseUsernameUseCase chooseUsername;
    @MockitoBean AuthenticationSessions sessions;

    /** The session of a browser that signed up for {@link #EMAIL}. */
    private static MockHttpSession signedUp() {
        var session = new MockHttpSession();
        session.setAttribute(
                RegistrationPage.ATTRIBUTE,
                new RegistrationPage(EMAIL, KEY, ID, true, Instant.now()));
        return session;
    }

    private static RegistrationPage held(MockHttpSession session) {
        return (RegistrationPage) session.getAttribute(RegistrationPage.ATTRIBUTE);
    }

    private String keyGivenToTheSignup() {
        var key = ArgumentCaptor.forClass(String.class);
        verify(startRegistration)
                .start(
                        eq(EMAIL),
                        eq("Une phrase fictive 1"),
                        eq("Une phrase fictive 1"),
                        key.capture(),
                        anyString());
        return key.getValue();
    }

    static Stream<Arguments> refusedRequests() {
        // Falsifié : le dépôt de jetons attend une valeur, et le client en envoie une autre.
        RequestPostProcessor noToken = request -> request;
        RequestPostProcessor forgedToken = CsrfTokens.forged();
        return Stream.of(
                argumentSet("inscription sans jeton", "/api/auth/signup", noToken),
                argumentSet("inscription avec jeton falsifié", "/api/auth/signup", forgedToken),
                argumentSet("renvoi sans jeton", "/api/auth/signup/resend", noToken),
                argumentSet("renvoi avec jeton falsifié", "/api/auth/signup/resend", forgedToken),
                argumentSet("confirmation sans jeton", "/api/auth/signup/confirm", noToken),
                argumentSet(
                        "confirmation avec jeton falsifié",
                        "/api/auth/signup/confirm",
                        forgedToken));
    }

    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("refusedRequests")
    void mutationWithoutAValidCsrfTokenIsRefusedAsCsrfInvalid(
            String path, RequestPostProcessor token) throws Exception {
        mvc.perform(post(path).session(signedUp()).with(token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_INVALID"));

        verifyNoInteractions(
                startRegistration, resendRegistrationCode, confirmRegistration, sessions);
    }

    @Test
    void signupKeepsTheRegistrationInTheSessionUnderAKeyOfTheServer() throws Exception {
        when(startRegistration.start(any(), any(), any(), any(), any()))
                .thenReturn(Duration.ofSeconds(60));
        var session = new MockHttpSession();

        mvc.perform(
                        post("/api/auth/signup")
                                .session(session)
                                .with(CsrfTokens.valid())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(SIGNUP))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Retry-After", "60"))
                .andExpect(header().string("Registration-Id", held(session).id()));

        String key = keyGivenToTheSignup();
        assertThat(held(session).emailKey()).as("email held").isEqualTo(EMAIL);
        assertThat(held(session).key()).as("key held").isEqualTo(key);
        assertThat(held(session).remembered()).as("choice held").isTrue();
        assertThat(key).as("key").matches("[A-Za-z0-9_-]{43}");
    }

    @Test
    void signupForTheEmailTheSessionHoldsDrawsANewKey() throws Exception {
        when(startRegistration.start(any(), any(), any(), any(), any()))
                .thenReturn(Duration.ofSeconds(60));
        var session = signedUp();

        mvc.perform(
                        post("/api/auth/signup")
                                .session(session)
                                .with(CsrfTokens.valid())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(SIGNUP))
                .andExpect(status().isAccepted());

        String key = keyGivenToTheSignup();
        assertThat(key).as("key given to the signup").isNotEqualTo(KEY);
        assertThat(held(session).key()).as("key held").isEqualTo(key);
    }

    static Stream<Arguments> refusalsOfTheQuota() {
        return Stream.of(
                argumentSet(
                        "email tout juste envoyé",
                        AuthenticationFailedException.mailRecentlySent(Duration.ofSeconds(40)),
                        "MAIL_RECENTLY_SENT"),
                argumentSet(
                        "quota dépassé",
                        AuthenticationFailedException.rateLimited(Duration.ofSeconds(40)),
                        "RATE_LIMITED"));
    }

    /** The code of the email already sent belongs to the page that asked for it. */
    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("refusalsOfTheQuota")
    void signupRefusedByTheQuotaKeepsNoRegistrationInTheSession(
            AuthenticationFailedException refusal, String code) throws Exception {
        when(startRegistration.start(any(), any(), any(), any(), any())).thenThrow(refusal);
        var session = new MockHttpSession();

        mvc.perform(
                        post("/api/auth/signup")
                                .session(session)
                                .with(CsrfTokens.valid())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(SIGNUP))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(header().doesNotExist("Registration-Id"));

        assertThat(held(session)).as("registration held").isNull();
    }

    @Test
    void resendAsksANewCodeForTheRegistrationOfTheSessionAndTellsTheWait() throws Exception {
        when(resendRegistrationCode.resend(any(), any(), any())).thenReturn(Duration.ofSeconds(60));

        mvc.perform(
                        post("/api/auth/signup/resend")
                                .session(signedUp())
                                .with(CsrfTokens.valid())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(RESEND))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Retry-After", "60"));

        verify(resendRegistrationCode).resend(eq(EMAIL), eq(KEY), anyString());
    }

    static Stream<Arguments> requestsWithoutTheRegistrationOfTheSession() {
        String otherTab = "{\"registration\":\"autre-onglet\",\"code\":\"123456\"}";
        String thisTab = "{\"registration\":\"" + ID + "\",\"code\":\"123456\"}";
        String oldPage = "{\"email\":\"fixture@example.invalid\",\"code\":\"123456\"}";
        Supplier<MockHttpSession> none = MockHttpSession::new;
        Supplier<MockHttpSession> signedUp = RegistrationControllerTest::signedUp;
        return Stream.of(
                argumentSet("renvoi sans session", "/api/auth/signup/resend", thisTab, none),
                argumentSet("confirmation sans session", "/api/auth/signup/confirm", thisTab, none),
                argumentSet(
                        "renvoi d'un autre onglet", "/api/auth/signup/resend", otherTab, signedUp),
                argumentSet(
                        "confirmation d'un autre onglet",
                        "/api/auth/signup/confirm",
                        otherTab,
                        signedUp),
                argumentSet(
                        "confirmation d'une page sans identifiant",
                        "/api/auth/signup/confirm",
                        oldPage,
                        signedUp));
    }

    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("requestsWithoutTheRegistrationOfTheSession")
    void requestWithoutTheRegistrationOfTheSessionIsRefusedAsGone(
            String path, String body, Supplier<MockHttpSession> session) throws Exception {
        mvc.perform(
                        post(path)
                                .session(session.get())
                                .with(CsrfTokens.valid())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("NO_PENDING_REGISTRATION"));

        verifyNoInteractions(resendRegistrationCode, confirmRegistration);
    }

    @Test
    void confirmationConfirmsTheRegistrationOfTheSessionWithItsChoiceAndForgetsIt()
            throws Exception {
        var grant =
                new AuthenticationGrant(
                        new AuthenticatedAccount(1, null, "Jeune", 0, UUID.randomUUID()), "secret");
        when(confirmRegistration.confirm(
                        anyString(), anyString(), anyString(), any(), any(), any(), anyString()))
                .thenReturn(grant);
        var result =
                mvc.perform(
                                post("/api/auth/signup/confirm")
                                        .session(signedUp())
                                        .with(CsrfTokens.valid())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"code\":\"123 456\",\"registration\":\"" + ID + "\"}"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.username").value("Jeune"))
                        .andReturn();
        var response = result.getResponse();

        assertThat(response.getHeaders("Set-Cookie"))
                .as("cookies set by the confirmation")
                .anyMatch(cookie -> cookie.startsWith(HostCookies.RECONNECTION_COOKIE + "="))
                .allSatisfy(HostCookies::assertHostOnly);
        assertThat(result.getRequest().getSession(false).getAttribute(RegistrationPage.ATTRIBUTE))
                .as("registration held by the new session")
                .isNull();
        verify(confirmRegistration)
                .confirm(
                        eq(EMAIL),
                        eq("123 456"),
                        eq(KEY),
                        eq(SessionPersistence.REMEMBERED),
                        any(),
                        any(),
                        anyString());
    }

    @Test
    void refusedConfirmationKeepsTheRegistrationOfTheSession() throws Exception {
        doThrow(new RegistrationFailedException(RegistrationFailedException.Reason.INVALID_CODE))
                .when(confirmRegistration)
                .confirm(anyString(), anyString(), anyString(), any(), any(), any(), anyString());
        var session = signedUp();

        mvc.perform(
                        post("/api/auth/signup/confirm")
                                .session(session)
                                .with(CsrfTokens.valid())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"code\":\"123456\",\"registration\":\"" + ID + "\"}"))
                .andExpect(status().isBadRequest());

        assertThat(held(session).key()).as("key held").isEqualTo(KEY);
    }

    @Test
    void confirmationWithoutCodeIsRefusedBeforeTheUseCase() throws Exception {
        mvc.perform(
                        post("/api/auth/signup/confirm")
                                .session(signedUp())
                                .with(CsrfTokens.valid())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(RESEND))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));

        verifyNoInteractions(startRegistration, resendRegistrationCode, confirmRegistration);
    }

    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("refusedTerms")
    void signupWithoutTheTermsAcceptedIsRefusedBeforeTheUseCase(String terms) throws Exception {
        String body = SIGNUP.replace(",\"acceptedTerms\":true", terms);

        mvc.perform(
                        post("/api/auth/signup")
                                .with(CsrfTokens.valid())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));

        verifyNoInteractions(startRegistration, resendRegistrationCode, confirmRegistration);
    }

    @Test
    void signupWithoutTheChoiceOfTheBoxIsRefusedBeforeTheUseCase() throws Exception {
        String body = SIGNUP.replace(",\"rememberMe\":true", "");

        mvc.perform(
                        post("/api/auth/signup")
                                .with(CsrfTokens.valid())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));

        verifyNoInteractions(startRegistration);
    }

    static Stream<Arguments> refusedTerms() {
        return Stream.of(
                argumentSet("case absente", ""),
                argumentSet("case non cochée", ",\"acceptedTerms\":false"));
    }
}
