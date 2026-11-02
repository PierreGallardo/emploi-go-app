package architecture.fixtures.config;

import architecture.fixtures.alpha.application.ApplicationFixtures;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

public final class ConfigurationFixtures {
    public static class Allowed {}

    public static class WithModule {
        ApplicationFixtures.Allowed useCase;
    }

    @Configuration
    public static class ClockConfiguration {}

    @ConfigurationProperties("fixture")
    public record LimitProperties(String value) {}
}
