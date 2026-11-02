package fr.emploigo.establishment.infrastructure.synchronization;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import fr.emploigo.establishment.infrastructure.synchronization.DatasetSynchronizationProperties.Source;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.net.URI;
import java.time.Duration;
import java.time.LocalTime;

class DatasetSynchronizationPropertiesTest {
    private static final Source ADDRESSES =
            new Source(
                    LocalTime.of(4, 10),
                    URI.create(
                            "https://adresse.data.gouv.fr/data/ban/adresses/latest/csv/adresses-21.csv.gz"));

    private static final Source TRAINING_ORGANIZATIONS =
            new Source(
                    LocalTime.of(4, 20),
                    URI.create(
                            "https://www.data.gouv.fr/api/1/datasets/r/ac59a0f5-fa83-4b82-bf12-3c5806d4f19f"));

    @Test
    void productionValuesAreAccepted() {
        assertThatCode(
                        () ->
                                new DatasetSynchronizationProperties(
                                        Duration.ofSeconds(5),
                                        Duration.ofMinutes(2),
                                        new Source(
                                                LocalTime.of(4, 0),
                                                URI.create(
                                                        "https://www.data.gouv.fr/api/1/datasets/r/e0dbd217-15cd-4e28-9459-211a27511a34")),
                                        ADDRESSES,
                                        TRAINING_ORGANIZATIONS))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @CsvSource(
            nullValues = "null",
            value = {
                "PT0S, PT2M, 04:00, https://www.data.gouv.fr/gtfs.zip",
                "PT2M, PT2M, 04:00, https://www.data.gouv.fr/gtfs.zip",
                "PT5S, PT11M, 04:00, https://www.data.gouv.fr/gtfs.zip",
                "PT5S, PT2M, null, https://www.data.gouv.fr/gtfs.zip",
                "PT5S, PT2M, 04:00, null",
                "PT5S, PT2M, 04:00, http://www.data.gouv.fr/gtfs.zip"
            })
    void invalidValueStopsTheStart(
            Duration connectTimeout, Duration downloadTimeout, LocalTime dailyAt, URI url) {
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                new DatasetSynchronizationProperties(
                                        connectTimeout,
                                        downloadTimeout,
                                        new Source(dailyAt, url),
                                        ADDRESSES,
                                        TRAINING_ORGANIZATIONS));
    }
}
