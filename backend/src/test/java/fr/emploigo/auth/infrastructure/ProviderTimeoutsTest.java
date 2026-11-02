package fr.emploigo.auth.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.mock.env.MockEnvironment;

import java.time.Duration;
import java.util.stream.Stream;

class ProviderTimeoutsTest {
    private static final String PROPERTY = "emploi-go.test.timeout";
    private static final Duration BY_DEFAULT = Duration.ofSeconds(7);

    @Test
    void absentPropertyKeepsTheDefault() {
        var timeout = ProviderTimeouts.read(new MockEnvironment(), PROPERTY, BY_DEFAULT);

        assertThat(timeout).as("timeout").isEqualTo(BY_DEFAULT);
    }

    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("acceptedDurations")
    void durationWithinTheBoundsIsAccepted(String value, Duration expected) {
        var environment = new MockEnvironment().withProperty(PROPERTY, value);

        var timeout = ProviderTimeouts.read(environment, PROPERTY, BY_DEFAULT);

        assertThat(timeout).as("timeout").isEqualTo(expected);
    }

    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("refusedDurations")
    void durationOutsideTheBoundsOrNotADurationIsRefused(String value) {
        var environment = new MockEnvironment().withProperty(PROPERTY, value);

        assertThatThrownBy(() -> ProviderTimeouts.read(environment, PROPERTY, BY_DEFAULT))
                .as("startup")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(PROPERTY);
    }

    static Stream<Arguments> acceptedDurations() {
        return Stream.of(
                argumentSet("minimum", "100ms", Duration.ofMillis(100)),
                argumentSet("secondes", "3s", Duration.ofSeconds(3)),
                argumentSet("maximum", "30s", Duration.ofSeconds(30)),
                argumentSet("format ISO", "PT2S", Duration.ofSeconds(2)));
    }

    static Stream<Arguments> refusedDurations() {
        return Stream.of(
                argumentSet("sous le minimum", "99ms"),
                argumentSet("au-dessus du maximum", "31s"),
                argumentSet("durée nulle", "0s"),
                argumentSet("nombre sans unité", "10"),
                argumentSet("texte quelconque", "bientôt"));
    }
}
