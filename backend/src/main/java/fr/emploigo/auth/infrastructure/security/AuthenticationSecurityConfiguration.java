package fr.emploigo.auth.infrastructure.security;

import static org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN;

import fr.emploigo.auth.application.session.AuthenticationCookies;
import fr.emploigo.auth.application.session.AuthenticationSessions;
import fr.emploigo.config.ApiOnly;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;
import org.springframework.security.web.authentication.logout.LogoutHandler;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionFixationProtectionStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;

import java.time.Clock;
import java.util.Map;

@Configuration(proxyBeanMethods = false)
@ApiOnly
public class AuthenticationSecurityConfiguration {
    private static final String ITERATIONS = "emploi-go.auth.password-hashing-iterations";
    private static final int MINIMUM_ITERATIONS = 1_000;
    private static final int MAXIMUM_ITERATIONS = 10_000_000;
    // The API serves no page: nothing in its responses may run or be framed.
    private static final String CONTENT_SECURITY_POLICY =
            "default-src 'none'; frame-ancestors 'none'";
    private static final String PERMISSIONS_POLICY =
            "camera=(), microphone=(), geolocation=(self)";

    /**
     * The iteration count is not stored with a hash: changing it on a database that holds hashes
     * makes every existing password fail.
     */
    @Bean
    PasswordEncoder passwordEncoder(@Value("${" + ITERATIONS + ":600000}") int iterations) {
        if (iterations < MINIMUM_ITERATIONS || iterations > MAXIMUM_ITERATIONS)
            throw new IllegalStateException(
                    "La propriété "
                            + ITERATIONS
                            + " doit rester entre "
                            + MINIMUM_ITERATIONS
                            + " et "
                            + MAXIMUM_ITERATIONS
                            + ".");
        var encoder =
                new Pbkdf2PasswordEncoder(
                        "",
                        16,
                        iterations,
                        Pbkdf2PasswordEncoder.SecretKeyFactoryAlgorithm.PBKDF2WithHmacSHA256);
        return new DelegatingPasswordEncoder("pbkdf2-sha256", Map.of("pbkdf2-sha256", encoder));
    }

    @Bean
    SecurityContextRepository authenticationContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    /**
     * Double-submit cookie with no server-side state, so anonymous visitors have no session; the
     * {@code __Host-} prefix (see {@link AuthenticationCookies}) stops sibling subdomains from
     * setting it.
     */
    @Bean
    CookieCsrfTokenRepository authenticationCsrfRepository(AuthenticationCookies cookies) {
        var repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repository.setCookieName(cookies.csrf());
        repository.setHeaderName("X-CSRF-TOKEN");
        repository.setCookiePath("/");
        repository.setCookieCustomizer(cookie -> cookie.secure(cookies.secure()).sameSite("Lax"));
        return repository;
    }

    @Bean
    SessionAuthenticationStrategy authenticationSessionStrategy() {
        // A new JDBC primary row stops an old request snapshot from restoring the old session ID;
        // BrowserAuthentication
        // renews the CSRF token in the same response.
        return new SessionFixationProtectionStrategy();
    }

    @Bean
    @Order(1)
    SecurityFilterChain authenticationFilterChain(
            HttpSecurity http,
            AuthenticationSessions sessions,
            Clock clock,
            SecurityContextRepository context,
            CookieCsrfTokenRepository csrf,
            LogoutHandler browser)
            throws Exception {
        http.securityMatcher("/api/**");
        http.authorizeHttpRequests(
                authorize ->
                        authorize
                                .requestMatchers(
                                        HttpMethod.GET,
                                        "/api/ping",
                                        "/api/auth/csrf",
                                        "/api/auth/google/callback/google",
                                        "/api/auth/google/status")
                                .permitAll()
                                .requestMatchers(
                                        HttpMethod.POST,
                                        "/api/auth/login",
                                        "/api/auth/resume",
                                        "/api/auth/signup",
                                        "/api/auth/signup/resend",
                                        "/api/auth/signup/confirm",
                                        "/api/auth/password/forgot",
                                        "/api/auth/password/reset",
                                        "/api/auth/google/start",
                                        "/api/auth/google/access",
                                        "/api/auth/google/signup",
                                        "/api/auth/google/link",
                                        "/api/auth/google/email",
                                        "/api/auth/google/email/confirm")
                                .permitAll()
                                .requestMatchers(HttpMethod.GET, "/api/auth/session")
                                .hasRole("JEUNE")
                                .requestMatchers(HttpMethod.POST, "/api/auth/username")
                                .hasRole("JEUNE")
                                .requestMatchers(
                                        HttpMethod.GET,
                                        "/api/establishments",
                                        "/api/establishments/points",
                                        "/api/establishments/suggestions",
                                        "/api/establishments/{siret}",
                                        "/api/places/suggestions",
                                        "/api/datasets",
                                        "/api/establishment-images/*")
                                .hasRole("JEUNE")
                                .anyRequest()
                                .denyAll());
        http.securityContext(configuration -> configuration.securityContextRepository(context));
        // spa() replaces the repository: keep csrfTokenRepository(csrf) after it.
        http.csrf(
                configuration ->
                        configuration
                                .spa()
                                .csrfTokenRepository(csrf));
        http.requestCache(AbstractHttpConfigurer::disable);
        http.headers(
                headers ->
                        headers.contentSecurityPolicy(
                                        policy -> policy.policyDirectives(CONTENT_SECURITY_POLICY))
                                .referrerPolicy(
                                        referrer ->
                                                referrer.policy(
                                                        STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                                .permissionsPolicyHeader(
                                        permissions -> permissions.policy(PERMISSIONS_POLICY)));
        http.logout(
                logout ->
                        logout.logoutUrl("/api/auth/logout")
                                .addLogoutHandler(browser)
                                .logoutSuccessHandler(
                                        new HttpStatusReturningLogoutSuccessHandler(
                                                HttpStatus.NO_CONTENT)));
        http.exceptionHandling(
                errors ->
                        errors.authenticationEntryPoint(
                                        (request, response, error) ->
                                                AuthenticationSessionFilter.writeError(
                                                        response,
                                                        401,
                                                        "UNAUTHENTICATED",
                                                        "Connecte-toi pour continuer."))
                                .accessDeniedHandler(
                                        (request, response, error) ->
                                                AuthenticationSessionFilter.writeError(
                                                        response,
                                                        403,
                                                        error
                                                                        instanceof
                                                                        org.springframework.security
                                                                                .web.csrf
                                                                                .CsrfException
                                                                ? "CSRF_INVALID"
                                                                : "FORBIDDEN",
                                                        "Action refusée. "
                                                                + "Recharge la page puis réessaie.")));
        http.addFilterBefore(new AuthenticationSessionFilter(sessions, clock), CsrfFilter.class);
        http.addFilterBefore(
                new AuthenticationAvailabilityFilter(),
                org.springframework.security.web.context.SecurityContextHolderFilter.class);
        return http.build();
    }
}
