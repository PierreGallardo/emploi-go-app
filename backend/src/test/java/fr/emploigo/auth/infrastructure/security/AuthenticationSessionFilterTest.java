package fr.emploigo.auth.infrastructure.security;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import fr.emploigo.auth.api.session.BrowserAuthentication;
import fr.emploigo.auth.application.session.AuthenticationCookies;
import fr.emploigo.auth.application.session.AuthenticationSessions;
import fr.emploigo.auth.domain.SessionIdentity;
import fr.emploigo.config.SecurityConfiguration;
import fr.emploigo.status.api.PingController;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

/** The session check covers the whole {@code /api/**} chain, the probe included. */
@WebMvcTest(
        controllers = PingController.class,
        properties =
                "spring.autoconfigure.exclude=org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration")
@Import({
    SecurityConfiguration.class,
    AuthenticationSessionFilterTest.FixedClock.class,
    AuthenticationSecurityConfiguration.class,
    BrowserAuthentication.class,
    AuthenticationCookies.class
})
@ActiveProfiles("test")
class AuthenticationSessionFilterTest {
    private static final Instant NOW = Instant.parse("2026-01-01T10:00:00Z");

    @TestConfiguration
    static class FixedClock {
        @Bean
        Clock clock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    @Autowired MockMvc mvc;
    @MockitoBean AuthenticationSessions sessions;

    @Test
    void pingWithASessionAnswers503WhenTheSessionCheckIsUnavailable() throws Exception {
        doThrow(new DataAccessResourceFailureException("base indisponible"))
                .when(sessions)
                .verify(anyLong(), anyLong(), any());
        var session = new MockHttpSession();
        session.setAttribute(
                SessionIdentity.ATTRIBUTE,
                new SessionIdentity(1, 1, UUID.randomUUID(), NOW, true));

        mvc.perform(get("/api/ping").session(session))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("Service temporairement indisponible."))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().contentTypeCompatibleWith("application/json"));
    }
}
