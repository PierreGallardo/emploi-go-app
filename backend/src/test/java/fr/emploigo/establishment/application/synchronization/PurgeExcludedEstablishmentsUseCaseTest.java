package fr.emploigo.establishment.application.synchronization;

import static fr.emploigo.establishment.domain.TestDirectoryEstablishment.CLASSIFIED;
import static fr.emploigo.establishment.domain.TestDirectoryEstablishment.retained;

import static org.assertj.core.api.Assertions.assertThat;

import fr.emploigo.establishment.domain.Assessment;
import fr.emploigo.establishment.domain.Establishment;
import fr.emploigo.establishment.domain.Siret;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.stream.IntStream;

class PurgeExcludedEstablishmentsUseCaseTest {
    private static final Instant NOW = Instant.parse("2026-10-25T04:30:00Z");

    private final FakeEstablishmentRepository establishments = new FakeEstablishmentRepository();
    private final PurgeExcludedEstablishmentsUseCase purge =
            new PurgeExcludedEstablishmentsUseCase(
                    establishments, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void establishmentExcludedForLongerThanTheRetentionIsDeleted() {
        establishments
                .excluded(establishment("12345678900012"), NOW.minus(Duration.ofDays(91)))
                .excluded(establishment("22345678900012"), NOW.minus(Duration.ofDays(89)))
                .with(establishment("32345678900012"), NOW.minus(Duration.ofDays(200)));

        int deleted = purge.purge();

        assertThat(deleted).isOne();
        assertThat(establishments.rows().keySet())
                .containsExactly(new Siret("22345678900012"), new Siret("32345678900012"));
    }

    @Test
    void purgeGoesOnBatchAfterBatchUntilNothingIsDue() {
        IntStream.rangeClosed(1, PurgeExcludedEstablishmentsUseCase.BATCH_SIZE + 1)
                .mapToObj(number -> establishment("%014d".formatted(number)))
                .forEach(due -> establishments.excluded(due, NOW.minus(Duration.ofDays(100))));

        int deleted = purge.purge();

        assertThat(deleted).isEqualTo(PurgeExcludedEstablishmentsUseCase.BATCH_SIZE + 1);
        assertThat(establishments.rows()).isEmpty();
    }

    private static Establishment establishment(String siret) {
        return ((Assessment.Retained) retained().siret(siret).build().assess(CLASSIFIED, Set.of()))
                .establishment();
    }
}
