package fr.emploigo.establishment.infrastructure.synchronization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.time.Duration;
import java.time.LocalTime;

class EstablishmentSynchronizationPropertiesTest {
    @Test
    void productionValuesAreAccepted() {
        assertThatCode(
                        () ->
                                new EstablishmentSynchronizationProperties(
                                        LocalTime.of(11, 0),
                                        URI.create("https://api.insee.fr/api-sirene/3.11"),
                                        "fictitious-key",
                                        Duration.ofSeconds(5),
                                        Duration.ofSeconds(30)))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @CsvSource(
            nullValues = "null",
            value = {
                "null, https://api.insee.fr/api-sirene/3.11, PT5S, PT30S",
                "11:00, null, PT5S, PT30S",
                "11:00, http://api.insee.fr/api-sirene/3.11, PT5S, PT30S",
                "11:00, ftp://127.0.0.1, PT5S, PT30S",
                "11:00, https://api.insee.fr/api-sirene/3.11, PT0S, PT30S",
                "11:00, https://api.insee.fr/api-sirene/3.11, PT5S, PT2M"
            })
    void invalidValueStopsTheStart(
            LocalTime dailyAt,
            URI directoryUrl,
            Duration connectTimeout,
            Duration responseTimeout) {
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                new EstablishmentSynchronizationProperties(
                                        dailyAt,
                                        directoryUrl,
                                        "fictitious-key",
                                        connectTimeout,
                                        responseTimeout));
    }

    @ParameterizedTest
    @CsvSource(
            nullValues = "null",
            value = {"null", "''", "'   '"})
    void blankKeyIsReadAsAbsent(String apiKey) {
        var properties = properties(apiKey);

        assertThat(properties.apiKey()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"first-key\nsecond-key", "two keys", "clé-accentuée"})
    void keyThatIsNotPlainTextStopsTheStartWithoutQuotingIt(String apiKey) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> properties(apiKey))
                .withMessageNotContaining(apiKey);
    }

    @Test
    void keyNeverAppearsInTheTextOfTheProperties() {
        var properties = properties("secret-of-the-test");

        assertThat(properties.toString()).doesNotContain("secret-of-the-test");
    }

    private static EstablishmentSynchronizationProperties properties(String apiKey) {
        return new EstablishmentSynchronizationProperties(
                LocalTime.of(11, 0),
                URI.create("https://api.insee.fr/api-sirene/3.11"),
                apiKey,
                Duration.ofSeconds(5),
                Duration.ofSeconds(30));
    }
}
