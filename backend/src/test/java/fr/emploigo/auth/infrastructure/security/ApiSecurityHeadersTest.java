package fr.emploigo.auth.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import fr.emploigo.auth.api.session.BrowserAuthentication;
import fr.emploigo.auth.application.session.AuthenticationCookies;
import fr.emploigo.auth.application.session.AuthenticationSessions;
import fr.emploigo.config.ClockConfiguration;
import fr.emploigo.config.SecurityConfiguration;
import fr.emploigo.status.api.PingController;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Arrays;
import java.util.List;

/**
 * En-têtes que Spring Security pose sur une réponse de la chaîne {@code /api/**} (docs/securite.md,
 * « Headers HTTP »).
 */
@WebMvcTest(
        controllers = PingController.class,
        properties =
                "spring.autoconfigure.exclude=org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration")
@Import({
    SecurityConfiguration.class,
    ClockConfiguration.class,
    AuthenticationSecurityConfiguration.class,
    BrowserAuthentication.class,
    AuthenticationCookies.class
})
@ActiveProfiles("test")
class ApiSecurityHeadersTest {
    @Autowired MockMvc mvc;
    @MockitoBean AuthenticationSessions sessions;

    @Test
    void apiResponseForbidsContentTypeSniffing() throws Exception {
        mvc.perform(get("/api/ping"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "Content-Security-Policy | default-src 'none'; frame-ancestors 'none'",
                "Referrer-Policy         | strict-origin-when-cross-origin",
                "Permissions-Policy      | camera=(), microphone=(), geolocation=(self)"
            })
    void apiResponseCarriesSecurityHeader(String name, String value) throws Exception {
        mvc.perform(get("/api/ping"))
                .andExpect(status().isOk())
                .andExpect(header().string(name, value));
    }

    /**
     * HSTS n'est posé que sur une requête sécurisée ({@code secure(true)}) ; en production, le
     * proxy TLS doit être dans {@code TRUSTED_PROXY_PATTERN} et envoyer {@code X-Forwarded-Proto:
     * https}.
     */
    @Test
    void secureApiResponseAnnouncesHstsForOneYearWithSubdomains() throws Exception {
        var response =
                mvc.perform(get("/api/ping").secure(true))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse();

        String hsts = response.getHeader("Strict-Transport-Security");

        assertThat(hsts).as("Strict-Transport-Security").isNotNull();
        List<String> directives = Arrays.stream(hsts.split(";")).map(String::trim).toList();
        assertThat(directives)
                .as("directives HSTS")
                .containsExactlyInAnyOrder("max-age=31536000", "includeSubDomains");
    }
}
