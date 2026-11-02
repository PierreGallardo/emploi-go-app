package fr.emploigo.establishment.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class CompanyTypeTest {
    @ParameterizedTest
    @CsvSource({
        "NAF_REV2, 78.20Z, TEMP_AGENCY",
        "NAF_2025, 78.20G, TEMP_AGENCY",
        "NAF_2025, 78.20H, COMPANY",
        "NAF_REV2, 78.10Z, RECRUITMENT",
        "NAF_2025, 78.10Y, RECRUITMENT",
        "NAF_REV2, 85.59A, TRAINING",
        "NAF_2025, 85.59G, TRAINING",
        "NAF_REV2, 62.01Z, IT_SERVICES",
        "NAF_REV2, 62.02A, IT_SERVICES",
        "NAF_2025, 62.10Y, IT_SERVICES",
        "NAF_2025, 62.20G, IT_SERVICES",
        "NAF_2025, 56.11G, COMPANY"
    })
    void activityGivesTheType(Nomenclature nomenclature, String code, CompanyType type) {
        assertThat(CompanyType.of(new ActivityCode(nomenclature, code), false)).isEqualTo(type);
    }

    @Test
    void establishmentThatDeclaredTrainingIsATrainingCentre() {
        assertThat(CompanyType.of(new ActivityCode(Nomenclature.NAF_2025, "56.11G"), true))
                .isEqualTo(CompanyType.TRAINING);
    }

    @Test
    void temporaryWorkAgencyThatAlsoTrainsStaysATemporaryWorkAgency() {
        assertThat(CompanyType.of(new ActivityCode(Nomenclature.NAF_2025, "78.20G"), true))
                .isEqualTo(CompanyType.TEMP_AGENCY);
    }
}
