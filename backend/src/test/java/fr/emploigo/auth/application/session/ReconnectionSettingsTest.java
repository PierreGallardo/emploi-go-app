package fr.emploigo.auth.application.session;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Duration;
import java.util.stream.Stream;

class ReconnectionSettingsTest {
    static Stream<Arguments> refusedTolerances() {
        return Stream.of(
                argumentSet("zéro", Duration.ZERO),
                argumentSet("sous la borne", Duration.ofMillis(999)),
                argumentSet("au-dessus de la borne", Duration.ofMinutes(1).plusMillis(1)));
    }

    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("refusedTolerances")
    void toleranceOutsideTheBoundsIsRefused(Duration tolerance) {
        assertThatThrownBy(() -> new ReconnectionSettings(tolerance))
                .as("construction")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("emploi-go.auth.reconnection-reuse-tolerance");
    }

    static Stream<Arguments> acceptedTolerances() {
        return Stream.of(
                argumentSet("borne basse", Duration.ofSeconds(1)),
                argumentSet("défaut", Duration.ofSeconds(30)),
                argumentSet("borne haute", Duration.ofMinutes(1)));
    }

    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("acceptedTolerances")
    void toleranceWithinTheBoundsIsAccepted(Duration tolerance) {
        assertThatCode(() -> new ReconnectionSettings(tolerance))
                .as("construction")
                .doesNotThrowAnyException();
    }
}
