package fr.emploigo.establishment.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class HeadcountRangeTest {
    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"NN", "", "99"})
    void codeWithoutEmployeeOrUnknownHasNoRange(String code) {
        assertThat(HeadcountRange.of(code)).isEmpty();
    }
}
