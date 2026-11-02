package fr.emploigo.auth.application.confirmation;

import fr.emploigo.auth.domain.AuthenticationSecrets;
import fr.emploigo.auth.domain.RegistrationCode;
import fr.emploigo.config.ApiOnly;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

@Component
@ApiOnly
public class ConfirmationTokens {
    private final ConfirmationKeySettings settings;

    public ConfirmationTokens(ConfirmationKeySettings settings) {
        this.settings = settings;
    }

    /**
     * The code sent for a registration operation: derived, so that a repeat sends the same email.
     */
    public String code(UUID operation) {
        return RegistrationCode.fromDigest(mac("registration-code:v1:" + operation));
    }

    /**
     * What the database keeps of a code. Keyed, because a hash of one million values is found by
     * trying them all.
     */
    public String codeCheck(UUID operation, String code) {
        return derive("registration-code-check:v1:" + operation + ":" + code);
    }

    public String recoveryToken(UUID operation) {
        return derive("password-recovery:v1:" + operation);
    }

    public String googleToken(UUID operation) {
        return derive("google-email-confirmation:v1:" + operation);
    }

    private String derive(String message) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mac(message));
    }

    private byte[] mac(String message) {
        if (!settings.enabled())
            throw new IllegalStateException("Le canal de courriel est désactivé.");
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(settings.tokenKey(), "HmacSHA256"));
            return mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.GeneralSecurityException error) {
            throw new IllegalStateException("Dérivation de confirmation indisponible.", error);
        }
    }

    public String keyId() {
        return AuthenticationSecrets.hash(
                Base64.getEncoder().encodeToString(settings.tokenKey()));
    }
}
