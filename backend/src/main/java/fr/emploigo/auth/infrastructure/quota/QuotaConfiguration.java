package fr.emploigo.auth.infrastructure.quota;

import fr.emploigo.auth.application.quota.IdentityLimits;
import fr.emploigo.auth.application.quota.QuotaLimits;
import fr.emploigo.auth.application.quota.QuotaScope;
import fr.emploigo.auth.application.quota.QuotaSettings;
import fr.emploigo.config.ApiOnly;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;

import java.util.EnumMap;

@ApiOnly
@Configuration
@EnableConfigurationProperties(QuotaProperties.class)
class QuotaConfiguration {
    @Bean
    QuotaEnvironmentCheck quotaEnvironmentCheck(ConfigurableEnvironment environment) {
        return new QuotaEnvironmentCheck(environment);
    }

    @Bean
    QuotaSettings quotaSettings(QuotaProperties properties) {
        var limits = new EnumMap<QuotaScope, QuotaLimits>(QuotaScope.class);
        var identityLimits = new EnumMap<QuotaScope, IdentityLimits>(QuotaScope.class);
        for (QuotaScope scope : QuotaScope.values()) {
            if (scope.identityOnly()) identityLimits.put(scope, properties.identityLimits(scope));
            else if (!scope.failureDelay()) limits.put(scope, properties.limits(scope));
        }
        return new QuotaSettings(properties.loginLimits(), limits, identityLimits);
    }
}
