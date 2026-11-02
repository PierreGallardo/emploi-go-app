package fr.emploigo.auth.infrastructure.purge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;

class PurgePropertiesTest {
    @ParameterizedTest
    @ValueSource(strings = {"PT1M", "PT5M", "PT1H"})
    void anIntervalWithinTheBoundsIsAccepted(String interval) {
        assertThat(new PurgeProperties(Duration.parse(interval)).interval())
                .as("interval")
                .isEqualTo(Duration.parse(interval));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PT0S", "PT59S", "PT1H1S", "PT24H"})
    void anIntervalOutOfTheBoundsRefusesTheStartWithTheNameOfTheProperty(String interval) {
        assertThatThrownBy(() -> new PurgeProperties(Duration.parse(interval)))
                .as("start")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("emploi-go.purge.interval");
    }

    @Test
    void anAbsentIntervalRefusesTheStartWithTheNameOfTheProperty() {
        assertThatThrownBy(() -> new PurgeProperties(null))
                .as("start")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("emploi-go.purge.interval");
    }
}
