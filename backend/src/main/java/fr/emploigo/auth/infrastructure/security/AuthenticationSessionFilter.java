package fr.emploigo.auth.infrastructure.security;

import fr.emploigo.auth.application.failure.AuthenticationFailedException;
import fr.emploigo.auth.application.session.AuthenticationSessions;
import fr.emploigo.auth.domain.SessionIdentity;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Clock;

public class AuthenticationSessionFilter extends OncePerRequestFilter {
    private static final Logger LOG = LoggerFactory.getLogger(AuthenticationSessionFilter.class);
    private final AuthenticationSessions sessions;
    private final Clock clock;

    public AuthenticationSessionFilter(AuthenticationSessions sessions, Clock clock) {
        this.sessions = sessions;
        this.clock = clock;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("Cache-Control", "no-store");
        try {
            var session = request.getSession(false);
            if (session != null
                    && session.getAttribute(SessionIdentity.ATTRIBUTE)
                            instanceof SessionIdentity identity) {
                var end = identity.startedAt().plus(AuthenticationSessions.SESSION_DURATION);
                if (!clock.instant().isBefore(end)) {
                    session.invalidate();
                    SecurityContextHolder.clearContext();
                } else {
                    sessions.verify(
                            identity.accountId(),
                            identity.credentialVersion(),
                            identity.familyId());
                }
            } else if (SecurityContextHolder.getContext().getAuthentication() != null) {
                // An authenticated context without our account/session proof is never sufficient.
                SecurityContextHolder.clearContext();
                if (session != null) session.invalidate();
            }
        } catch (AuthenticationFailedException error) {
            var session = request.getSession(false);
            if (session != null) session.invalidate();
            SecurityContextHolder.clearContext();
        } catch (DataAccessException error) {
            LOG.error(
                    "Vérification de session indisponible ({})", error.getClass().getSimpleName());
            writeError(response, 503, "UNAVAILABLE", "Service temporairement indisponible.");
            return;
        }
        chain.doFilter(request, response);
    }

    static void writeError(HttpServletResponse response, int status, String code, String message)
            throws IOException {
        response.setStatus(status);
        response.setHeader("Cache-Control", "no-store");
        response.setContentType("application/json");
        response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}");
    }
}
