package fr.emploigo.config;

import jakarta.servlet.DispatcherType;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;

@Configuration(proxyBeanMethods = false)
@ApiAndWorker
public class SecurityConfiguration {
    @Bean
    @Order(2)
    SecurityFilterChain securityFilterChain(HttpSecurity http, Environment environment)
            throws Exception {
        boolean worker = ApplicationRole.of(environment) == ApplicationRole.WORKER;
        if (worker) {
            http.sessionManagement(
                    session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
            http.requestCache(AbstractHttpConfigurer::disable);
            http.csrf(AbstractHttpConfigurer::disable);
        }
        http.authorizeHttpRequests(
                authorize -> {
                    authorize.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll();
                    authorize
                            .requestMatchers("/actuator/health", "/actuator/health/**")
                            .permitAll();
                    if (!worker) {
                        authorize.requestMatchers(HttpMethod.GET, "/api/ping").permitAll();
                    }
                    if (!worker
                            && environment.acceptsProfiles(
                                    Profiles.of("local", "contract", "test"))) {
                        authorize
                                .requestMatchers(
                                        "/v3/api-docs",
                                        "/v3/api-docs/**",
                                        "/swagger-ui.html",
                                        "/swagger-ui/**")
                                .permitAll();
                    }
                    authorize.anyRequest().denyAll();
                });
        http.exceptionHandling(
                errors ->
                        errors.authenticationEntryPoint(
                                new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)));
        // Aucun formulaire, compte par défaut ou jeton d'authentification implicite.
        return http.build();
    }
}
