package fr.emploigo.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.HttpCookie;

/**
 * The production cookies of the API: the {@code __Host-} prefix forces {@code Secure}, {@code
 * Path=/} and no {@code Domain}, so a sibling subdomain cannot set them; {@link Browser} checks
 * every {@code Set-Cookie} it receives, so a test that exercises a controller with MockMvc must
 * check its own cookies. The integration tests run without the {@code local} profile, so they see
 * these names.
 */
public final class HostCookies {
    public static final String SESSION_COOKIE = "__Host-EMPLOIGO_SESSION";
    public static final String RECONNECTION_COOKIE = "__Host-EMPLOIGO_RECONNECTION";

    /** Double-submit CSRF token, readable by the frontend script. */
    public static final String CSRF_COOKIE = "__Host-XSRF-TOKEN";

    /**
     * Tells the frontend a reconnection is possible: not secret, readable by the frontend script.
     */
    public static final String RECONNECTABLE_COOKIE = "__Host-EMPLOIGO_RECONNECTABLE";

    private static final String PREFIX = "__Host-";

    private HostCookies() {}

    public static void assertHostOnly(String setCookie) {
        var cookie = HttpCookie.parse(setCookie).getFirst();
        assertThat(cookie.getName()).as("name of the cookie in '%s'", setCookie).startsWith(PREFIX);
        assertThat(cookie.getSecure()).as("Secure attribute of %s", cookie.getName()).isTrue();
        assertThat(cookie.getPath()).as("Path attribute of %s", cookie.getName()).isEqualTo("/");
        assertThat(cookie.getDomain()).as("Domain attribute of %s", cookie.getName()).isNull();
    }
}
