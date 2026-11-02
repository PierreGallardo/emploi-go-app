package fr.emploigo.auth.application.session;

import fr.emploigo.config.ApiOnly;

import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * Outside the {@code local} profile (plain HTTP) the cookies are {@code Secure} with the {@code
 * __Host-} prefix, so a sibling subdomain cannot set a cookie of the same name (see {@code
 * docs/securite.md}, "Cookies et sous-domaines").
 */
@Component
@ApiOnly
public class AuthenticationCookies {
    private static final String HOST_PREFIX = "__Host-";
    private final boolean secure;
    private final String session;
    private final String reconnection;
    private final String csrf;
    private final String reconnectable;

    public AuthenticationCookies(Environment environment) {
        this.secure = !environment.acceptsProfiles(Profiles.of("local"));
        String prefix = secure ? HOST_PREFIX : "";
        this.session = environment.getRequiredProperty("server.servlet.session.cookie.name");
        if (secure) requireHostOnlySessionCookie(environment);
        this.reconnection = prefix + "EMPLOIGO_RECONNECTION";
        this.csrf = prefix + "XSRF-TOKEN";
        this.reconnectable = prefix + "EMPLOIGO_RECONNECTABLE";
    }

    /**
     * Spring Session sets the session cookie from the configuration: refuse a production setting
     * that drops the rule.
     */
    private void requireHostOnlySessionCookie(Environment environment) {
        String prefix = "server.servlet.session.cookie.";
        boolean hostOnly =
                session.startsWith(HOST_PREFIX)
                        && environment.getProperty(prefix + "secure", Boolean.class, false)
                        && "/".equals(environment.getProperty(prefix + "path"))
                        && environment.getProperty(prefix + "domain") == null;
        if (!hostOnly) {
            throw new IllegalStateException(
                    "The session cookie must have the __Host- prefix, be Secure, have Path=/ and have no Domain.");
        }
    }

    public boolean secure() {
        return secure;
    }

    public String session() {
        return session;
    }

    /** Reconnection secret, {@code HttpOnly}. */
    public String reconnection() {
        return reconnection;
    }

    /**
     * CSRF token, readable by the frontend script: the double-submit cookie of {@code
     * CookieCsrfTokenRepository}.
     */
    public String csrf() {
        return csrf;
    }

    /**
     * Tells the frontend a reconnection is possible. Not secret, readable by the frontend script.
     */
    public String reconnectable() {
        return reconnectable;
    }
}
