package fr.emploigo.config;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import fr.emploigo.status.api.PingController;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(
        controllers = PingController.class,
        properties =
                "spring.autoconfigure.exclude=org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration")
@Import(SecurityConfiguration.class)
@ActiveProfiles("test")
class SecurityConfigurationTest {
    @Autowired MockMvc mvc;

    @Test
    void anonymousPingReturnsOnlyItsTechnicalStatus() throws Exception {
        mvc.perform(get("/api/ping"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(content().json("{\"status\":\"ok\"}"));
    }

    @Test
    void pingDoesNotPermitMutationsEvenWithCsrf() throws Exception {
        mvc.perform(post("/api/ping").with(csrf())).andExpect(status().isUnauthorized());
    }

    @Test
    void anonymousApiRequestIsUnauthorized() throws Exception {
        mvc.perform(get("/api/private")).andExpect(status().isUnauthorized());
    }

    @Test
    void mutationWithoutCsrfIsForbidden() throws Exception {
        mvc.perform(post("/api/private")).andExpect(status().isForbidden());
    }

    @Test
    void prometheusIsNotPublic() throws Exception {
        mvc.perform(get("/actuator/prometheus")).andExpect(status().isUnauthorized());
    }
}
