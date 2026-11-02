package fr.emploigo.auth.api;

import static fr.emploigo.support.HostCookies.CSRF_COOKIE;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import fr.emploigo.auth.application.AuthenticationIntegrationTest;
import fr.emploigo.support.CsrfTokens;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.List;
import java.util.stream.Stream;

/**
 * Double-submit cookie: the {@code __Host-} prefix keeps a sibling subdomain from planting the
 * value that the header is checked against.
 */
class CsrfProtectionIT extends AuthenticationIntegrationTest {
    /**
     * Every {@code POST} route of {@code /api/auth}, relative to it. {@link
     * #listCoversEveryPostRoute} keeps it complete.
     */
    private static final List<String> POST_ROUTES =
            List.of(
                    "login",
                    "resume",
                    "logout",
                    "signup",
                    "signup/resend",
                    "signup/confirm",
                    "username",
                    "password/forgot",
                    "password/reset",
                    "google/start",
                    "google/access",
                    "google/signup",
                    "google/link",
                    "google/email",
                    "google/email/confirm");

    /** The {@code POST} routes of {@code POST_ROUTES} that a filter answers, not a controller. */
    private static final List<String> FILTER_ROUTES = List.of("logout");

    /**
     * The filter is global, so the variants below use one route: {@link
     * #requestWithoutCookieNorHeaderAnswers403CsrfInvalid} covers every route.
     */
    private static final String ANY_ROUTE = "login";

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mappings;

    static Stream<Arguments> routes() {
        return POST_ROUTES.stream().map(route -> argumentSet(route, route));
    }

    @Test
    void listCoversEveryPostRoute() {
        var routes =
                mappings.getHandlerMethods().keySet().stream()
                        .filter(
                                mapping ->
                                        mapping.getMethodsCondition()
                                                .getMethods()
                                                .contains(RequestMethod.POST))
                        .flatMap(
                                mapping ->
                                        mapping
                                                .getPathPatternsCondition()
                                                .getPatternValues()
                                                .stream())
                        .filter(path -> path.startsWith("/api/auth/"))
                        .map(path -> path.substring("/api/auth/".length()));

        assertThat(Stream.concat(routes, FILTER_ROUTES.stream()).toList())
                .as("POST routes of /api/auth")
                .containsExactlyInAnyOrderElementsOf(POST_ROUTES);
    }

    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("routes")
    void requestWithoutCookieNorHeaderAnswers403CsrfInvalid(String route) throws Exception {
        var browser = newBrowser();

        var response = browser.send("POST", route, "{}", null);

        assertThat(response.statusCode()).as("status").isEqualTo(403);
        assertThat(code(response)).as("code").isEqualTo("CSRF_INVALID");
    }

    @Test
    void requestWithTheCookieButWithoutTheHeaderAnswers403CsrfInvalid() throws Exception {
        var browser = newBrowser();
        browser.csrf();

        var response = browser.send("POST", ANY_ROUTE, "{}", null);

        assertThat(response.statusCode()).as("status").isEqualTo(403);
        assertThat(code(response)).as("code").isEqualTo("CSRF_INVALID");
    }

    @Test
    void requestWithAHeaderThatIsNotTheCookieAnswers403CsrfInvalid() throws Exception {
        var browser = newBrowser();
        browser.csrf();

        var response = browser.send("POST", ANY_ROUTE, "{}", "forged");

        assertThat(response.statusCode()).as("status").isEqualTo(403);
        assertThat(code(response)).as("code").isEqualTo("CSRF_INVALID");
    }

    @Test
    void requestWithAHeaderEqualToACookieWithoutThePrefixAnswers403CsrfInvalid() throws Exception {
        // What a sibling subdomain can set: the cookie of the same name, without the __Host-
        // prefix.
        var browser = newBrowser();
        browser.cookies.put("XSRF-TOKEN", "planted");

        var response = browser.send("POST", ANY_ROUTE, "{}", "planted");

        assertThat(response.statusCode()).as("status").isEqualTo(403);
        assertThat(code(response)).as("code").isEqualTo("CSRF_INVALID");
    }

    @Test
    void formWithAForgedTokenParameterAnswers403CsrfInvalid() throws Exception {
        var browser = newBrowser();
        browser.csrf();

        var response = browser.postForm(ANY_ROUTE, "_csrf=forged", null);

        assertThat(response.statusCode()).as("status").isEqualTo(403);
        assertThat(code(response)).as("code").isEqualTo("CSRF_INVALID");
    }

    @Test
    void formWithTheValueOfTheCookieAsTokenParameterAnswers403CsrfInvalid() throws Exception {
        // Only the masked form is read from the parameter: the raw value is not one.
        var browser = newBrowser();
        browser.csrf();

        var response =
                browser.postForm(ANY_ROUTE, "_csrf=" + browser.cookies.get(CSRF_COOKIE), null);

        assertThat(response.statusCode()).as("status").isEqualTo(403);
        assertThat(code(response)).as("code").isEqualTo("CSRF_INVALID");
    }

    @Test
    void formWithTheMaskedTokenParameterIsNotRefusedAsCsrf() throws Exception {
        // Another site cannot read the cookie, so it cannot build this value.
        var browser = newBrowser();
        browser.csrf();

        var response =
                browser.postForm(
                        ANY_ROUTE,
                        "_csrf=" + CsrfTokens.masked(browser.cookies.get(CSRF_COOKIE)),
                        null);

        assertThat(response.body()).as("body").doesNotContain("CSRF_INVALID");
    }

    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("routes")
    void requestWithAHeaderEqualToTheCookieIsNotRefusedAsCsrf(String route) throws Exception {
        var browser = newBrowser();

        var response = browser.send("POST", route, "{}", browser.csrf());

        assertThat(response.body()).as("body").doesNotContain("CSRF_INVALID");
    }

    @Test
    void requestWithAHeaderEqualToTheCookieReachesTheController() throws Exception {
        var browser = newBrowser();

        var response = browser.send("POST", "resume", "", browser.csrf());

        assertThat(response.statusCode()).as("status").isEqualTo(401);
        assertThat(code(response)).as("code").isEqualTo("RECONNECTION_REFUSED");
    }
}
