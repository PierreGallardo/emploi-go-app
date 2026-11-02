package fr.emploigo.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.web.csrf.DefaultCsrfToken;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;

/**
 * The masked token of {@link CsrfTokens} must be one Spring Security unmasks: otherwise a test that
 * refuses it proves nothing.
 */
class CsrfTokensTest {
    @Test
    void maskedTokenIsUnmaskedToTheTokenByTheHandlerOfSpringSecurity() {
        String token = "11111111-1111-4111-8111-111111111111";
        var request = new MockHttpServletRequest();
        request.setParameter("_csrf", CsrfTokens.masked(token));

        String resolved =
                new XorCsrfTokenRequestAttributeHandler()
                        .resolveCsrfTokenValue(
                                request, new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", token));

        assertThat(resolved).as("unmasked token").isEqualTo(token);
    }
}
