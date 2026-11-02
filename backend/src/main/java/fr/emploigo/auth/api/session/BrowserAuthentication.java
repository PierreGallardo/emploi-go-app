package fr.emploigo.auth.api.session;

import fr.emploigo.auth.application.session.AuthenticationCookies;
import fr.emploigo.auth.application.session.AuthenticationGrant;
import fr.emploigo.auth.application.session.AuthenticationSessions;
import fr.emploigo.auth.domain.RegistrationPage;
import fr.emploigo.auth.domain.SessionIdentity;
import fr.emploigo.config.ApiOnly;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import org.springframework.http.ResponseCookie;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.LogoutHandler;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

@Component
@ApiOnly
public class BrowserAuthentication implements LogoutHandler {
    /** Session attribute holding the browser proof, set when an external login starts. */
    public static final String BROWSER_PROOF = "auth.browser.proof";

    private final AuthenticationCookies cookies;
    private final SecurityContextRepository contexts;
    private final SessionAuthenticationStrategy strategy;
    private final CsrfTokenRepository csrf;
    private final Clock clock;
    private final AuthenticationSessions sessions;

    public BrowserAuthentication(
            SecurityContextRepository contexts,
            SessionAuthenticationStrategy strategy,
            CsrfTokenRepository csrf,
            Clock clock,
            AuthenticationCookies cookies,
            AuthenticationSessions sessions) {
        this.contexts = contexts;
        this.strategy = strategy;
        this.csrf = csrf;
        this.clock = clock;
        this.cookies = cookies;
        this.sessions = sessions;
    }

    public void establish(
            AuthenticationGrant grant,
            boolean explicit,
            HttpServletRequest request,
            HttpServletResponse response) {
        establish(grant, explicit, request, response, false);
    }

    public void establish(
            AuthenticationGrant grant,
            boolean explicit,
            HttpServletRequest request,
            HttpServletResponse response,
            boolean preserveBrowserProof) {
        if (!preserveBrowserProof) principalChanged(request, grant.account().familyId());
        var authentication =
                UsernamePasswordAuthenticationToken.authenticated(
                        Long.toString(grant.account().id()),
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_JEUNE")));
        strategy.onAuthentication(authentication, request, response);
        var session = request.getSession(true);
        // The new session id keeps the attributes: the registration of the visitor must not follow
        // the account into its session.
        session.removeAttribute(RegistrationPage.ATTRIBUTE);
        session.setAttribute(
                SessionIdentity.ATTRIBUTE,
                new SessionIdentity(
                        grant.account().id(),
                        grant.account().credentialVersion(),
                        grant.account().familyId(),
                        clock.instant(),
                        explicit));
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        contexts.saveContext(context, request, response);
        renewCsrf(request, response);
        if (grant.reconnectionCookie() == null) {
            // A cookie of an earlier login on this browser names a family the login just revoked.
            forgetReconnection(response);
            return;
        }
        long duration = AuthenticationSessions.RECONNECTION_DURATION.toSeconds();
        cookie(response, cookies.reconnection(), grant.reconnectionCookie(), duration, true);
        // The frontend resumes only when this cookie is present.
        cookie(response, cookies.reconnectable(), "1", duration, false);
    }

    public void rotateBrowserProof(HttpServletRequest request, HttpServletResponse response) {
        // Google has established a sensitive identity proof, even before a local account exists.
        strategy.onAuthentication(
                UsernamePasswordAuthenticationToken.unauthenticated("google-pending", null),
                request,
                response);
        renewCsrf(request, response);
    }

    public void clear(HttpServletRequest request, HttpServletResponse response) {
        principalChanged(request, null);
        var session = request.getSession(false);
        if (session != null) session.invalidate();
        SecurityContextHolder.clearContext();
        forgetBrowser(response);
        // Like a logout: the frontend asks for a new token before its next write.
        csrf.saveToken(null, request, response);
    }

    /**
     * Runs before {@code SecurityContextLogoutHandler}, which invalidates the session that names
     * the family to revoke.
     */
    @Override
    public void logout(
            HttpServletRequest request,
            HttpServletResponse response,
            Authentication authentication) {
        // A database outage propagates against the LogoutHandler contract: answered 503 before any
        // cookie is expired, the browser keeps what it needs to retry the revocation.
        principalChanged(request, null);
        sessions.revoke(reconnection(request), family(request));
        forgetBrowser(response);
    }

    private void forgetBrowser(HttpServletResponse response) {
        cookie(response, cookies.session(), "", 0, true);
        forgetReconnection(response);
    }

    private void forgetReconnection(HttpServletResponse response) {
        cookie(response, cookies.reconnection(), "", 0, true);
        clearReconnectable(response);
    }

    /** The reconnection is refused or revoked: the frontend must stop calling {@code /resume}. */
    public void clearReconnectable(HttpServletResponse response) {
        cookie(response, cookies.reconnectable(), "", 0, false);
    }

    /**
     * The previous principal's CSRF token must not outlive this one; it is sent in the same
     * response.
     */
    private void renewCsrf(HttpServletRequest request, HttpServletResponse response) {
        csrf.saveToken(csrf.generateToken(request), request, response);
    }

    private void principalChanged(HttpServletRequest request, UUID retainedFamily) {
        var session = request.getSession(false);
        if (session != null) sessions.principalChanged(browserProof(session), retainedFamily);
    }

    /**
     * The browser proof of {@code session}: the one a provider start kept, otherwise
     * derived from the session.
     */
    public String browserProof(HttpSession session) {
        return session.getAttribute(BROWSER_PROOF) instanceof String value
                ? value
                : sessions.browserProof(session.getId());
    }

    public UUID family(HttpServletRequest request) {
        var session = request.getSession(false);
        return session != null
                        && session.getAttribute(SessionIdentity.ATTRIBUTE)
                                instanceof SessionIdentity identity
                ? identity.familyId()
                : null;
    }

    public String reconnection(HttpServletRequest request) {
        if (request.getCookies() != null)
            for (Cookie cookie : request.getCookies()) {
                if (cookies.reconnection().equals(cookie.getName())) return cookie.getValue();
            }
        return null;
    }

    /** Always {@code Path=/} and no {@code Domain}: required by the {@code __Host-} prefix. */
    private void cookie(
            HttpServletResponse response,
            String name,
            String value,
            long seconds,
            boolean httpOnly) {
        response.addHeader(
                "Set-Cookie",
                ResponseCookie.from(name, value)
                        .httpOnly(httpOnly)
                        .secure(cookies.secure())
                        .sameSite("Lax")
                        .path("/")
                        .maxAge(seconds)
                        .build()
                        .toString());
    }
}
