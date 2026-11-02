package fr.emploigo.establishment.application.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import fr.emploigo.establishment.domain.Sector;
import fr.emploigo.establishment.domain.Siret;

import org.junit.jupiter.api.Test;

import java.util.List;

class ReadEstablishmentUseCaseTest {
    private static final Siret SIRET = new Siret("12345678900012");

    private final FakePublishedEstablishmentRepository establishments =
            new FakePublishedEstablishmentRepository();
    private final ReadEstablishmentUseCase read = new ReadEstablishmentUseCase(establishments);

    @Test
    void establishmentOffTheMapIsNotFound() {
        assertThatThrownBy(() -> read.read(SIRET))
                .isInstanceOf(EstablishmentNotFoundException.class);
    }

    @Test
    void namesAndAddressOfThePageAreShownInTheCaseOfFrench() {
        establishments.add(
                new EstablishmentDetail(
                        SIRET,
                        "REF 161529",
                        "SOCIETE FICTIVE",
                        Sector.RETAIL,
                        "Commerce de détail",
                        "1 RUE FICTIVE 21000 DIJON",
                        "Dijon",
                        null,
                        null,
                        null,
                        null,
                        List.of(),
                        null,
                        null,
                        null,
                        false,
                        null));

        var detail = read.read(SIRET);

        assertThat(detail.name()).isEqualTo("Societe Fictive");
        assertThat(detail.address()).isEqualTo("1 Rue Fictive 21000 Dijon");
    }
}
