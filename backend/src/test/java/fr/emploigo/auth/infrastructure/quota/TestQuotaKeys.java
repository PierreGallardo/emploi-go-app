package fr.emploigo.auth.infrastructure.quota;

import org.springframework.mock.env.MockEnvironment;

import java.util.Base64;

/**
 * The key the integration context uses too, so a test names a counter key as the application does.
 */
public final class TestQuotaKeys {
    public static final String KEY =
            Base64.getEncoder()
                    .encodeToString(
                            "quota-keys-for-tests-only-0123456"
                                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    public static final QuotaKeys KEYS =
            new QuotaKeys(new MockEnvironment().withProperty("emploi-go.auth.quota-key", KEY));

    private TestQuotaKeys() {}
}
