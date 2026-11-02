package fr.emploigo.auth.infrastructure.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.mock.env.MockEnvironment;

import java.time.Duration;
import java.util.function.Function;
import java.util.stream.Stream;

class AuthenticationMailConfigurationTest {
    private static final String PREFIX = "emploi-go.registration.";

    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("timeouts")
    void providerTimeoutDefaultsKeepTheValuesOfTheProviderCalls(
            String property,
            Duration byDefault,
            Function<AuthenticationMailConfiguration, Duration> accessor) {
        var configuration = new AuthenticationMailConfiguration(new MockEnvironment());

        assertThat(accessor.apply(configuration)).as(property).isEqualTo(byDefault);
    }

    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("timeouts")
    void providerTimeoutTakesTheConfiguredDuration(
            String property,
            Duration ignored,
            Function<AuthenticationMailConfiguration, Duration> accessor) {
        var configuration =
                new AuthenticationMailConfiguration(
                        new MockEnvironment().withProperty(PREFIX + property, "500ms"));

        assertThat(accessor.apply(configuration)).as(property).isEqualTo(Duration.ofMillis(500));
    }

    static Stream<Arguments> timeouts() {
        Function<AuthenticationMailConfiguration, Duration> mailConnect =
                AuthenticationMailConfiguration::mailConnectTimeout;
        Function<AuthenticationMailConfiguration, Duration> mailResponse =
                AuthenticationMailConfiguration::mailResponseTimeout;
        return Stream.of(
                argumentSet(
                        "email : connexion",
                        "mail-connect-timeout",
                        Duration.ofSeconds(3),
                        mailConnect),
                argumentSet(
                        "email : réponse",
                        "mail-response-timeout",
                        Duration.ofSeconds(10),
                        mailResponse));
    }
}
