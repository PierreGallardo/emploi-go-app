package fr.emploigo.establishment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class SiretTest {
    @Test
    void fourteenDigitsAreASiret() {
        assertThat(Siret.of("12345678900012")).contains(new Siret("12345678900012"));
    }

    @Test
    void malformedValueIsNoSiret() {
        assertThat(Siret.of("1234567890001A")).isEmpty();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "1234567890001", "123456789000123", "1234567890001A"})
    void valueOtherThanFourteenDigitsIsRefused(String value) {
        assertThatIllegalArgumentException().isThrownBy(() -> new Siret(value));
    }
}
