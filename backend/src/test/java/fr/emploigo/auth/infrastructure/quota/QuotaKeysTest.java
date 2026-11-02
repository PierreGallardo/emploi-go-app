package fr.emploigo.auth.infrastructure.quota;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import fr.emploigo.auth.domain.AuthenticationSecrets;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

import java.util.Arrays;
import java.util.Base64;

class QuotaKeysTest {
    private static String key(int length, byte fill) {
        var bytes = new byte[length];
        Arrays.fill(bytes, fill);
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static QuotaKeys keys(String key) {
        return new QuotaKeys(new MockEnvironment().withProperty(QuotaKeys.PROPERTY, key));
    }

    @Test
    void sameValueGivesTheSameKeyOfSixtyFourHexadecimalDigits() {
        assertThat(TestQuotaKeys.KEYS.identity("jeune@example.invalid"))
                .isEqualTo(TestQuotaKeys.KEYS.identity("jeune@example.invalid"))
                .matches("[0-9a-f]{64}");
    }

    @Test
    void valueIsNotItsPlainDigest() {
        assertThat(TestQuotaKeys.KEYS.address("203.0.113.7"))
                .isNotEqualTo(AuthenticationSecrets.hash("203.0.113.7"))
                .isNotEqualTo(AuthenticationSecrets.hash("ip:203.0.113.7"));
    }

    @Test
    void kindsKeepTheSameTextFromNamingTheSameKey() {
        var keys = TestQuotaKeys.KEYS;

        assertThat(keys.address("v6:20010db80001000"))
                .isNotEqualTo(keys.prefix48("v6:20010db80001"))
                .isNotEqualTo(keys.identity("v6:20010db80001"));
    }

    @Test
    void anotherSecretNamesAnotherKey() {
        assertThat(keys(key(32, (byte) 1)).identity("jeune@example.invalid"))
                .isNotEqualTo(keys(key(32, (byte) 2)).identity("jeune@example.invalid"));
    }

    @ParameterizedTest(name = "[{index}]")
    @ValueSource(strings = {"", "pas du base64 !", "AAAA"})
    void missingMalformedOrShortKeyRefusesTheStart(String value) {
        assertThatThrownBy(() -> keys(value)).isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest(name = "{0} octets")
    @ValueSource(ints = {31, 65})
    void keyOutsideThirtyTwoToSixtyFourBytesRefusesTheStart(int length) {
        assertThatThrownBy(() -> keys(key(length, (byte) 1)))
                .isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest(name = "{0} octets")
    @ValueSource(ints = {32, 64})
    void keyOfThirtyTwoToSixtyFourBytesIsAccepted(int length) {
        assertThat(keys(key(length, (byte) 1)).identity("jeune@example.invalid"))
                .matches("[0-9a-f]{64}");
    }

    @Test
    void digestIsTheHmacSha256OfTheKindAndTheValue() throws Exception {
        var mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(
                new javax.crypto.spec.SecretKeySpec(
                        Base64.getDecoder().decode(TestQuotaKeys.KEY), "HmacSHA256"));
        String expected =
                java.util.HexFormat.of()
                        .formatHex(
                                mac.doFinal(
                                        "identity:jeune@example.invalid"
                                                .getBytes(
                                                        java.nio.charset.StandardCharsets.UTF_8)));

        assertThat(TestQuotaKeys.KEYS.identity("jeune@example.invalid")).isEqualTo(expected);
    }

    @Test
    void apiConfigurationHasNoDefaultKeyAndOnlyTheLocalOneHasADevelopmentKey() throws Exception {
        var loader = new org.springframework.boot.env.YamlPropertySourceLoader();
        var api =
                loader.load(
                                "api",
                                new org.springframework.core.io.ClassPathResource(
                                        "application-api.yml"))
                        .getFirst();
        var local =
                loader.load(
                        "local",
                        new org.springframework.core.io.ClassPathResource("application-local.yml"));

        assertThat(api.getProperty(QuotaKeys.PROPERTY)).as("api").isEqualTo("${QUOTA_KEY:}");
        assertThat(
                        local.stream()
                                .map(source -> source.getProperty(QuotaKeys.PROPERTY))
                                .filter(java.util.Objects::nonNull))
                .as("local")
                .singleElement()
                .asString()
                .startsWith("${QUOTA_KEY:");
    }

    @Test
    void absentPropertyRefusesTheStart() {
        assertThatThrownBy(() -> new QuotaKeys(new MockEnvironment()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void messageNeverCarriesTheKey() {
        String secret = key(8, (byte) 7);

        assertThatThrownBy(() -> keys(secret)).message().doesNotContain(secret);
    }
}
