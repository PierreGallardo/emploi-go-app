package fr.emploigo.auth.infrastructure.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

public class AuthenticationAvailabilityFilter extends OncePerRequestFilter {
    private static final Logger LOG =
            LoggerFactory.getLogger(AuthenticationAvailabilityFilter.class);

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        try {
            chain.doFilter(request, response);
        } catch (DataAccessException | org.springframework.transaction.TransactionException error) {
            LOG.error(
                    "Dépendance d'authentification indisponible ({})",
                    error.getClass().getSimpleName());
            if (response.isCommitted()) throw error;
            response.resetBuffer();
            AuthenticationSessionFilter.writeError(
                    response, 503, "UNAVAILABLE", "Service temporairement indisponible.");
        }
    }
}
