package fr.emploigo.auth.infrastructure.quota;

import fr.emploigo.config.ApiOnly;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The only way a quota counter names an identity or an address in Redis: a keyed digest, so that
 * reading Redis does not give back an email, a username or an IP address. A limiter never hashes a
 * value itself.
 */
@Component
@ApiOnly
public class QuotaKeys {
    static final String PROPERTY = "emploi-go.auth.quota-key";

    private final byte[] key;

    public QuotaKeys(Environment environment) {
        String encoded = environment.getProperty(PROPERTY, "");
        if (encoded.isBlank())
            throw new IllegalStateException(
                    "QUOTA_KEY requise : clé des quotas de 32 à 64 octets en Base64.");
        try {
            key = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException error) {
            throw new IllegalStateException("QUOTA_KEY invalide : Base64 attendu.");
        }
        if (key.length < 32 || key.length > 64)
            throw new IllegalStateException("Clé des quotas de 32 à 64 octets requise.");
    }

    /** A client network: an IPv4 address, or the /60 prefix of an IPv6 one. */
    public String address(String network) {
        return digest("ip", network);
    }

    public String prefix48(String prefix) {
        return digest("prefix48", prefix);
    }

    public String identity(String identity) {
        return digest("identity", identity);
    }

    /** A page that asks to confirm an email: the key it sends is a secret, kept out of Redis. */
    public String confirmationPage(String emailKey, String confirmationKey) {
        return digest("confirmation-page", emailKey + "\0" + confirmationKey);
    }

    /** The kind keeps the same text in two roles from naming the same key. */
    private String digest(String kind, String value) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return HexFormat.of()
                    .formatHex(mac.doFinal((kind + ":" + value).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("Clé des quotas inutilisable.", error);
        }
    }
}
