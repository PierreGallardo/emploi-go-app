package fr.emploigo.auth.infrastructure.security;

import fr.emploigo.auth.application.development.CreateDevelopmentAccountUseCase;
import fr.emploigo.config.ApiOnly;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@ApiOnly
@Profile("local")
@ConditionalOnProperty(name = "emploi-go.auth.development-account", havingValue = "true")
public class DevelopmentAuthenticationConfiguration {
    @Bean
    ApplicationRunner createDevelopmentAuthenticationAccount(
            CreateDevelopmentAccountUseCase accounts, @Value("${DEV_AUTH_PASSWORD:}") String password) {
        if (password.length() < 20 || password.length() > 128)
            throw new IllegalArgumentException(
                    "DEV_AUTH_PASSWORD doit contenir 20 à 128 caractères");
        return args -> accounts.create(password);
    }
}
