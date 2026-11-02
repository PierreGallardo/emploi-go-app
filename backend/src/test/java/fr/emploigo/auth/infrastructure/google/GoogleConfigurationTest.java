package fr.emploigo.auth.infrastructure.google;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;


import fr.emploigo.auth.application.google.GoogleSettings;
import fr.emploigo.auth.infrastructure.mail.AuthenticationMailConfiguration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.mock.env.MockEnvironment;

import java.time.Duration;
import java.util.function.Function;
import java.util.stream.Stream;

class GoogleConfigurationTest {
    private AuthenticationMailConfiguration mail(boolean enabled) {
        var mail = mock(AuthenticationMailConfiguration.class);
        when(mail.origin()).thenReturn("http://localhost:5173");
        when(mail.enabled()).thenReturn(enabled);
        return mail;
    }

    private MockEnvironment enabledEnvironment() {
        return new MockEnvironment()
                .withProperty("emploi-go.google.enabled", "true")
                .withProperty("emploi-go.google.client-id", "fixture-client")
                .withProperty("emploi-go.google.client-secret", "fixture-secret")
                .withProperty(
                        "emploi-go.google.redirect-uri",
                        "http://localhost:5173/api/auth/google/callback/google");
    }

    @Test
    void googleIsDisabledWithoutItsConfiguration() {
        var configuration = new GoogleConfiguration(new MockEnvironment(), mail(true));

        assertThat(configuration.enabled()).as("enabled").isFalse();
    }

    @Test
    void settingsGiveTheDisabledStateAndTheOriginOfTheMailChannel() {
        var settings = new GoogleConfiguration(new MockEnvironment(), mail(true)).googleSettings();

        assertThat(settings)
                .as("settings")
                .isEqualTo(new GoogleSettings(false, "http://localhost:5173"));
    }

    @Test
    void disabledGoogleHasNoClientRegistration() {
        var configuration = new GoogleConfiguration(new MockEnvironment(), mail(true));

        assertThatThrownBy(configuration::registration)
                .as("client registration")
                .isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("requiredProperties")
    void enabledGoogleWithoutARequiredPropertyIsRefused(String property) {
        var environment = enabledEnvironment().withProperty("emploi-go.google." + property, "");

        assertThatThrownBy(() -> new GoogleConfiguration(environment, mail(true)))
                .isInstanceOf(IllegalStateException.class);
    }

    static Stream<Arguments> requiredProperties() {
        return Stream.of(
                argumentSet("identifiant client vide", "client-id"),
                argumentSet("secret client vide", "client-secret"),
                argumentSet("adresse de retour vide", "redirect-uri"));
    }

    @Test
    void enabledGoogleRefusesACallbackOutsideTheApplicationOrigin() {
        var environment =
                enabledEnvironment()
                        .withProperty(
                                "emploi-go.google.redirect-uri",
                                "http://localhost:8080/api/auth/google/callback/google");

        assertThatThrownBy(() -> new GoogleConfiguration(environment, mail(true)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void enabledGoogleRefusesADisabledMailChannel() {
        assertThatThrownBy(() -> new GoogleConfiguration(enabledEnvironment(), mail(false)))
                .isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("timeouts")
    void providerTimeoutDefaultsKeepTheValuesOfTheProviderCalls(
            String property, Duration byDefault, Function<GoogleConfiguration, Duration> accessor) {
        var configuration = new GoogleConfiguration(new MockEnvironment(), mail(true));

        assertThat(accessor.apply(configuration)).as(property).isEqualTo(byDefault);
    }

    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("timeouts")
    void providerTimeoutTakesTheConfiguredDuration(
            String property, Duration ignored, Function<GoogleConfiguration, Duration> accessor) {
        var environment =
                new MockEnvironment().withProperty("emploi-go.google." + property, "500ms");

        var configuration = new GoogleConfiguration(environment, mail(true));

        assertThat(accessor.apply(configuration)).as(property).isEqualTo(Duration.ofMillis(500));
    }

    static Stream<Arguments> timeouts() {
        Function<GoogleConfiguration, Duration> connect = GoogleConfiguration::connectTimeout;
        Function<GoogleConfiguration, Duration> response = GoogleConfiguration::responseTimeout;
        return Stream.of(
                argumentSet("connexion", "connect-timeout", Duration.ofSeconds(3), connect),
                argumentSet("réponse", "response-timeout", Duration.ofSeconds(10), response));
    }

    @Test
    void enabledGoogleRegistersOpenIdAndEmailScopes() {
        var registration = new GoogleConfiguration(enabledEnvironment(), mail(true)).registration();

        assertThat(registration.getScopes())
                .as("scopes")
                .containsExactlyInAnyOrder("openid", "email");
    }

    @Test
    void enabledGoogleRegistersTheGoogleIssuer() {
        var registration = new GoogleConfiguration(enabledEnvironment(), mail(true)).registration();

        assertThat(registration.getProviderDetails().getIssuerUri())
                .as("issuer")
                .isEqualTo("https://accounts.google.com");
    }

    @Test
    void enabledGoogleRegistersTheEndpointsOfGoogleWithTheTokenEndpointOfItsDiscovery() {
        var details =
                new GoogleConfiguration(enabledEnvironment(), mail(true))
                        .registration()
                        .getProviderDetails();

        assertThat(details.getAuthorizationUri())
                .as("authorization")
                .isEqualTo("https://accounts.google.com/o/oauth2/v2/auth");
        assertThat(details.getTokenUri())
                .as("token")
                .isEqualTo("https://oauth2.googleapis.com/token");
        assertThat(details.getJwkSetUri())
                .as("keys")
                .isEqualTo("https://www.googleapis.com/oauth2/v3/certs");
    }

    @Test
    void enabledGoogleRegistersTheExactCallbackAndTheSecretInTheBody() {
        var registration = new GoogleConfiguration(enabledEnvironment(), mail(true)).registration();

        assertThat(registration.getRedirectUri())
                .as("redirect")
                .isEqualTo("http://localhost:5173/api/auth/google/callback/google");
        assertThat(registration.getClientAuthenticationMethod().getValue())
                .as("client authentication")
                .isEqualTo("client_secret_post");
    }
}
