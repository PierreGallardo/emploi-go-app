package fr.emploigo.auth.infrastructure.google;

import fr.emploigo.auth.application.google.GoogleSettings;
import fr.emploigo.auth.infrastructure.ProviderTimeouts;
import fr.emploigo.auth.infrastructure.mail.AuthenticationMailConfiguration;
import fr.emploigo.config.ApiOnly;

import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Duration;

@Component
@ApiOnly
public class GoogleConfiguration {
    public static final String CALLBACK = "/api/auth/google/callback/google";
    // Google's discovery document gives this token endpoint; Spring still has the old /v4 one.
    private static final String TOKEN_URI = "https://oauth2.googleapis.com/token";
    private final boolean enabled;
    private final String origin;
    private final ClientRegistration registration;
    private final Duration connectTimeout;
    private final Duration responseTimeout;

    public GoogleConfiguration(Environment environment, AuthenticationMailConfiguration mail) {
        enabled = environment.getProperty("emploi-go.google.enabled", Boolean.class, false);
        connectTimeout =
                ProviderTimeouts.read(
                        environment, "emploi-go.google.connect-timeout", Duration.ofSeconds(3));
        responseTimeout =
                ProviderTimeouts.read(
                        environment, "emploi-go.google.response-timeout", Duration.ofSeconds(10));
        origin = mail.origin();
        if (!enabled) {
            registration = null;
            return;
        }
        if (!mail.enabled())
            throw new IllegalStateException(
                    "Google exige le canal de courriel d'authentification.");
        String clientId = environment.getProperty("emploi-go.google.client-id", "");
        String secret = environment.getProperty("emploi-go.google.client-secret", "");
        String redirect = environment.getProperty("emploi-go.google.redirect-uri", "");
        if (clientId.isBlank() || secret.isBlank() || !redirect.equals(origin + CALLBACK))
            throw new IllegalStateException(
                    "Client Google ou callback autorisé manquant/invalide.");
        var official =
                CommonOAuth2Provider.GOOGLE
                        .getBuilder("google")
                        .clientId(clientId)
                        .clientSecret(secret)
                        .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
                        .redirectUri(redirect)
                        .scope("openid", "email")
                        .tokenUri(TOKEN_URI)
                        .build();
        registration =
                environment.acceptsProfiles(Profiles.of("test"))
                        ? withTestProvider(environment, official)
                        : official;
    }

    private static ClientRegistration withTestProvider(
            Environment environment, ClientRegistration official) {
        var details = official.getProviderDetails();
        return ClientRegistration.withClientRegistration(official)
                .authorizationUri(
                        endpoint(environment, "authorization-uri", details.getAuthorizationUri()))
                .tokenUri(endpoint(environment, "token-uri", details.getTokenUri()))
                .jwkSetUri(endpoint(environment, "jwk-set-uri", details.getJwkSetUri()))
                .issuerUri(
                        environment.getProperty(
                                "emploi-go.google.issuer-uri", details.getIssuerUri()))
                .build();
    }

    private static String endpoint(
            Environment environment, String name, String official) {
        String value = environment.getProperty("emploi-go.google." + name, official);
        URI uri = URI.create(value);
        if (!value.equals(official)
                && !("http".equals(uri.getScheme())
                        && java.util.Set.of("localhost", "127.0.0.1").contains(uri.getHost())))
            throw new IllegalStateException("Fournisseur de test Google invalide.");
        return value;
    }

    /** The values that the use cases of the Google flows read. */
    @Bean
    GoogleSettings googleSettings() {
        return new GoogleSettings(enabled, origin);
    }

    public boolean enabled() {
        return enabled;
    }

    public Duration connectTimeout() {
        return connectTimeout;
    }

    public Duration responseTimeout() {
        return responseTimeout;
    }

    public ClientRegistration registration() {
        if (!enabled) throw new IllegalStateException("Google est désactivé.");
        return registration;
    }
}
