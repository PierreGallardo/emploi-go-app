package fr.emploigo.auth.infrastructure.session;

import fr.emploigo.auth.application.session.ReconnectionSettings;
import fr.emploigo.config.ApiOnly;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@ApiOnly
@Configuration
class SessionConfiguration {
    @Bean
    ReconnectionSettings reconnectionSettings(
            @Value("${emploi-go.auth.reconnection-reuse-tolerance:30s}") Duration reuseTolerance) {
        return new ReconnectionSettings(reuseTolerance);
    }
}
