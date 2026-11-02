package fr.emploigo.establishment.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class DisplayNameTest {
    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "BOULANGERIE DE LA GARE | Boulangerie de la Gare",
                "L'ATELIER DU PAIN | L'Atelier du Pain",
                "GARAGE D'OUCHE | Garage d'Ouche",
                "SNCF VOYAGEURS | SNCF Voyageurs",
                "CHU DIJON BOURGOGNE | CHU Dijon Bourgogne",
                "EHPAD LES OPALINES | EHPAD les Opalines",
                "SARL MARTIN | SARL Martin",
                "CHEVIGNY-SAINT-SAUVEUR | Chevigny-Saint-Sauveur",
                "12 RUE DE LA LIBERTE 21000 DIJON | 12 Rue de la Liberte 21000 Dijon",
                "LE BISTROT | Le Bistrot",
                "Harmonie Mutuelle | Harmonie Mutuelle",
                "iPhone CLINIC | iPhone CLINIC"
            })
    void textInCapitalsTakesTheCaseOfFrenchAndAWrittenTextIsKept(String text, String shown) {
        assertThat(DisplayName.of(text)).isEqualTo(shown);
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "REF 161529 | SOCIETE FICTIVE | Societe Fictive",
                "161529 | SOCIETE FICTIVE | Societe Fictive",
                "BOULANGERIE 2000 | SOCIETE FICTIVE | Boulangerie 2000"
            })
    void signThatIsACodeGivesWayToTheCompanyName(String sign, String company, String shown) {
        assertThat(DisplayName.ofEstablishment(sign, company)).isEqualTo(shown);
    }
}
