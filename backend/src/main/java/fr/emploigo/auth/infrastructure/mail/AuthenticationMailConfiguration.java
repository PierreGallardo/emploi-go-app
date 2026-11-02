package fr.emploigo.auth.infrastructure.mail;

import fr.emploigo.auth.application.confirmation.ConfirmationKeySettings;
import fr.emploigo.auth.application.mail.AuthenticationMailSettings;
import fr.emploigo.auth.infrastructure.ProviderEndpoints;
import fr.emploigo.auth.infrastructure.ProviderTimeouts;
import fr.emploigo.config.ApiOnly;

import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Duration;
import java.util.Base64;

@Component
@ApiOnly
public class AuthenticationMailConfiguration {
    private final boolean enabled;
    private final String mode;
    private final String sender;
    private final String origin;
    private final String apiKey;
    private final URI endpoint;
    private final byte[] tokenKey;
    private final Duration mailConnectTimeout;
    private final Duration mailResponseTimeout;

    public AuthenticationMailConfiguration(Environment environment) {
        mode = environment.getProperty("emploi-go.registration.mail-mode", "disabled");
        enabled = !mode.equals("disabled");
        boolean local = environment.acceptsProfiles(Profiles.of("local", "test"));
        if (!java.util.Set.of("disabled", "local", "resend").contains(mode)
                || mode.equals("local") && !local)
            throw new IllegalStateException("Canal de courriel non autorisé.");
        sender =
                environment.getProperty(
                        "emploi-go.registration.sender", "confirmation@example.invalid");
        origin =
                environment.getProperty(
                        "emploi-go.registration.public-origin", "http://localhost:5173");
        apiKey = environment.getProperty("emploi-go.registration.resend-key", "");
        endpoint =
                URI.create(
                        mode.equals("local")
                                ? environment.getProperty(
                                        "emploi-go.registration.local-mail-url",
                                        "http://127.0.0.1:8025/emails")
                                : "https://api.resend.com/emails");
        mailConnectTimeout =
                ProviderTimeouts.read(
                        environment,
                        "emploi-go.registration.mail-connect-timeout",
                        Duration.ofSeconds(3));
        mailResponseTimeout =
                ProviderTimeouts.read(
                        environment,
                        "emploi-go.registration.mail-response-timeout",
                        Duration.ofSeconds(10));
        if (enabled) {
            ProviderEndpoints.validateOrigin(URI.create(origin), local);
            if (!sender.matches("[^\\s@<>]+@[^\\s@<>]+\\.[^\\s@<>]+") || sender.length() > 320)
                throw new IllegalStateException("Expéditeur de confirmation invalide.");
            if (mode.equals("local") && !ProviderEndpoints.isLoopback(endpoint))
                throw new IllegalStateException("Le canal local doit rester sur localhost.");
            if (mode.equals("resend") && apiKey.isBlank())
                throw new IllegalStateException("Clé Resend requise.");
            try {
                tokenKey =
                        Base64.getDecoder()
                                .decode(
                                        environment.getProperty(
                                                "emploi-go.registration.token-key", ""));
            } catch (IllegalArgumentException error) {
                throw new IllegalStateException("Clé de confirmation invalide.");
            }
            if (tokenKey.length < 32 || tokenKey.length > 64)
                throw new IllegalStateException("Clé de confirmation de 32 à 64 octets requise.");
        } else tokenKey = new byte[0];
    }

    public boolean enabled() {
        return enabled;
    }

    public String sender() {
        return sender;
    }

    public String origin() {
        return origin;
    }

    public String apiKey() {
        return apiKey;
    }

    public URI endpoint() {
        return endpoint;
    }

    public byte[] tokenKey() {
        return tokenKey.clone();
    }

    public Duration mailConnectTimeout() {
        return mailConnectTimeout;
    }

    public Duration mailResponseTimeout() {
        return mailResponseTimeout;
    }

    /** The values that the use cases of the authentication emails read. */
    @Bean
    AuthenticationMailSettings authenticationMailSettings() {
        return new AuthenticationMailSettings(enabled, sender, origin);
    }

    @Bean
    ConfirmationKeySettings confirmationKeySettings() {
        return new ConfirmationKeySettings(enabled, tokenKey());
    }
}
