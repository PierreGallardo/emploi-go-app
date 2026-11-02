package fr.emploigo.auth.api.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;
import static org.mockito.Mockito.mock;

import fr.emploigo.auth.application.session.AuthenticatedAccount;
import fr.emploigo.auth.application.session.AuthenticationCookies;
import fr.emploigo.auth.application.session.AuthenticationGrant;
import fr.emploigo.auth.application.session.AuthenticationSessions;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;

import java.net.HttpCookie;
import java.time.Clock;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Cookie names with the {@code __Host-} prefix in production, and without it under the {@code
 * local} profile, where plain HTTP forbids {@code Secure}.
 */
class BrowserAuthenticationTest {
    private final MockHttpServletRequest request = new MockHttpServletRequest();
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    static Stream<Arguments> environments() {
        return Stream.of(
                argumentSet("production", "__Host-", true), argumentSet("profil local", "", false));
    }

    private BrowserAuthentication browser(String prefix, boolean secure) {
        var environment =
                new MockEnvironment()
                        .withProperty(
                                "server.servlet.session.cookie.name", prefix + "EMPLOIGO_SESSION")
                        .withProperty(
                                "server.servlet.session.cookie.secure", String.valueOf(secure))
                        .withProperty("server.servlet.session.cookie.path", "/");
        environment.setActiveProfiles(secure ? new String[0] : new String[] {"local"});
        return browser(environment);
    }

    private BrowserAuthentication browser(MockEnvironment environment) {
        return new BrowserAuthentication(
                mock(SecurityContextRepository.class),
                mock(SessionAuthenticationStrategy.class),
                csrf(environment),
                Clock.systemUTC(),
                new AuthenticationCookies(environment),
                mock(AuthenticationSessions.class));
    }

    /**
     * Only the name of the cookie matters here: its attributes are those of the real configuration,
     * checked by the IT.
     */
    private CookieCsrfTokenRepository csrf(MockEnvironment environment) {
        var repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repository.setCookieName(new AuthenticationCookies(environment).csrf());
        return repository;
    }

    private String header(String name) {
        return response.getHeaders("Set-Cookie").stream()
                .filter(value -> value.startsWith(name + "="))
                .findFirst()
                .orElseThrow();
    }

    private HttpCookie cookie(String name) {
        return HttpCookie.parse(header(name)).getFirst();
    }

    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("environments")
    void establishSetsTheReconnectionCookieForTheWholeSite(String prefix, boolean secure) {
        var grant =
                new AuthenticationGrant(
                        new AuthenticatedAccount(1, null, "jeune", 0, UUID.randomUUID()), "secret");

        browser(prefix, secure).establish(grant, true, request, response);

        var cookie = cookie(prefix + "EMPLOIGO_RECONNECTION");
        assertThat(cookie.getValue()).as("value").isEqualTo("secret");
        assertThat(cookie.getPath()).as("path").isEqualTo("/");
        assertThat(cookie.getDomain()).as("domain").isNull();
        assertThat(cookie.getSecure()).as("secure").isEqualTo(secure);
        assertThat(cookie.isHttpOnly()).as("http only").isTrue();
        assertThat(header(prefix + "EMPLOIGO_RECONNECTION")).as("header").contains("SameSite=Lax");
    }

    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("environments")
    void establishSetsTheReconnectableIndicatorForTheDurationOfTheReconnection(
            String prefix, boolean secure) {
        var grant =
                new AuthenticationGrant(
                        new AuthenticatedAccount(1, null, "jeune", 0, UUID.randomUUID()), "secret");

        browser(prefix, secure).establish(grant, true, request, response);

        var cookie = cookie(prefix + "EMPLOIGO_RECONNECTABLE");
        assertThat(cookie.getValue()).as("value").isEqualTo("1");
        assertThat(cookie.getMaxAge())
                .as("max age")
                .isEqualTo(AuthenticationSessions.RECONNECTION_DURATION.toSeconds());
        assertThat(cookie.getPath()).as("path").isEqualTo("/");
        assertThat(cookie.getDomain()).as("domain").isNull();
        assertThat(cookie.getSecure()).as("secure").isEqualTo(secure);
        assertThat(cookie.isHttpOnly()).as("http only").isFalse();
        assertThat(header(prefix + "EMPLOIGO_RECONNECTABLE")).as("header").contains("SameSite=Lax");
    }

    static Stream<Arguments> reconnectionCookies() {
        return Stream.of(
                argumentSet("reconnexion en production", "__Host-", true, "EMPLOIGO_RECONNECTION"),
                argumentSet("indicateur en production", "__Host-", true, "EMPLOIGO_RECONNECTABLE"),
                argumentSet("reconnexion en local", "", false, "EMPLOIGO_RECONNECTION"),
                argumentSet("indicateur en local", "", false, "EMPLOIGO_RECONNECTABLE"));
    }

    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("reconnectionCookies")
    void establishWithoutReconnectionCookieExpiresTheReconnectionCookies(
            String prefix, boolean secure, String name) {
        var grant =
                new AuthenticationGrant(
                        new AuthenticatedAccount(1, null, "jeune", 0, UUID.randomUUID()), null);

        browser(prefix, secure).establish(grant, true, request, response);

        var cookie = cookie(prefix + name);
        assertThat(cookie.getValue()).as("value").isEmpty();
        assertThat(cookie.getMaxAge()).as("max age").isZero();
        assertThat(cookie.getPath()).as("path").isEqualTo("/");
    }

    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("environments")
    void establishWritesANewCsrfCookieUnderTheNameOfTheEnvironment(String prefix, boolean secure) {
        var grant =
                new AuthenticationGrant(
                        new AuthenticatedAccount(1, null, "jeune", 0, UUID.randomUUID()), "secret");

        browser(prefix, secure).establish(grant, true, request, response);

        assertThat(cookie(prefix + "XSRF-TOKEN").getValue()).as("value").isNotBlank();
    }

    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("environments")
    void clearExpiresTheCsrfCookieWithoutWritingANewOne(String prefix, boolean secure) {
        request.setCookies(new jakarta.servlet.http.Cookie(prefix + "XSRF-TOKEN", "previous"));

        browser(prefix, secure).clear(request, response);

        assertThat(response.getHeaders("Set-Cookie"))
                .as("CSRF set-cookie headers")
                .filteredOn(value -> value.startsWith(prefix + "XSRF-TOKEN="))
                .singleElement()
                .satisfies(
                        value ->
                                assertThat(HttpCookie.parse(value).getFirst().getMaxAge())
                                        .as("max age")
                                        .isZero());
    }

    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("environments")
    void clearReconnectableExpiresOnlyTheIndicator(String prefix, boolean secure) {
        browser(prefix, secure).clearReconnectable(response);

        assertThat(response.getHeaders("Set-Cookie"))
                .as("set-cookie headers")
                .singleElement()
                .satisfies(
                        value -> {
                            var cookie = HttpCookie.parse(value).getFirst();
                            assertThat(cookie.getName())
                                    .as("name")
                                    .isEqualTo(prefix + "EMPLOIGO_RECONNECTABLE");
                            assertThat(cookie.getMaxAge()).as("max age").isZero();
                        });
    }

    static Stream<Arguments> clearedCookies() {
        return Stream.of(
                argumentSet("session en production", "__Host-", true, "EMPLOIGO_SESSION"),
                argumentSet("reconnexion en production", "__Host-", true, "EMPLOIGO_RECONNECTION"),
                argumentSet("indicateur en production", "__Host-", true, "EMPLOIGO_RECONNECTABLE"),
                argumentSet("session en local", "", false, "EMPLOIGO_SESSION"),
                argumentSet("reconnexion en local", "", false, "EMPLOIGO_RECONNECTION"),
                argumentSet("indicateur en local", "", false, "EMPLOIGO_RECONNECTABLE"));
    }

    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("clearedCookies")
    void clearExpiresTheCookieUnderItsNameAtTheRoot(String prefix, boolean secure, String name) {
        browser(prefix, secure).clear(request, response);

        var cookie = cookie(prefix + name);
        assertThat(cookie.getMaxAge()).as("max age").isZero();
        assertThat(cookie.getPath()).as("path").isEqualTo("/");
        assertThat(cookie.getDomain()).as("domain").isNull();
        assertThat(cookie.getSecure()).as("secure").isEqualTo(secure);
    }

    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("clearedCookies")
    void logoutExpiresTheCookieUnderItsNameAtTheRoot(String prefix, boolean secure, String name) {
        browser(prefix, secure).logout(request, response, null);

        var cookie = cookie(prefix + name);
        assertThat(cookie.getMaxAge()).as("max age").isZero();
        assertThat(cookie.getPath()).as("path").isEqualTo("/");
        assertThat(cookie.getDomain()).as("domain").isNull();
        assertThat(cookie.getSecure()).as("secure").isEqualTo(secure);
    }

    static Stream<Arguments> driftingSessionCookieSettings() {
        return Stream.of(
                argumentSet(
                        "sans préfixe", "server.servlet.session.cookie.name", "EMPLOIGO_SESSION"),
                argumentSet("sans Secure", "server.servlet.session.cookie.secure", "false"),
                argumentSet("chemin plus étroit", "server.servlet.session.cookie.path", "/api"),
                argumentSet("avec Domain", "server.servlet.session.cookie.domain", "emploigo.fr"));
    }

    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("driftingSessionCookieSettings")
    void productionRefusesASessionCookieThatIsNotHostOnly(String property, String value) {
        var environment =
                new MockEnvironment()
                        .withProperty(
                                "server.servlet.session.cookie.name", "__Host-EMPLOIGO_SESSION")
                        .withProperty("server.servlet.session.cookie.secure", "true")
                        .withProperty("server.servlet.session.cookie.path", "/")
                        .withProperty(property, value);

        assertThatThrownBy(() -> new AuthenticationCookies(environment))
                .as("startup")
                .isInstanceOf(IllegalStateException.class);
    }
}
