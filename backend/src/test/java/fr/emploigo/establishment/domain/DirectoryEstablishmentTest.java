package fr.emploigo.establishment.domain;

import static fr.emploigo.establishment.domain.TestDirectoryEstablishment.CLASSIFIED;
import static fr.emploigo.establishment.domain.TestDirectoryEstablishment.RESTAURANT;
import static fr.emploigo.establishment.domain.TestDirectoryEstablishment.retained;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.LocalDate;
import java.util.Set;
import java.util.stream.Stream;

class DirectoryEstablishmentTest {
    @Test
    void openEmployerOfAClassifiedActivityIsRetainedWithItsData() {
        Assessment assessment = retained().build().assess(CLASSIFIED, Set.of());

        assertThat(assessment)
                .isEqualTo(
                        new Assessment.Retained(
                                new Establishment(
                                        new Siret("12345678900012"),
                                        "LE BISTROT FICTIF",
                                        "SOCIETE FICTIVE",
                                        "1 RUE FICTIVE 21000 DIJON",
                                        "21231",
                                        new Position(5.04, 47.32),
                                        RESTAURANT,
                                        CompanyType.COMPANY,
                                        new HeadcountRange("11"),
                                        "PME",
                                        LocalDate.of(2011, 3, 1),
                                        true)));
    }

    @Test
    void establishmentOpposedToDiffusionIsWithheld() {
        Assessment assessment =
                retained().diffusible(false).build().assess(CLASSIFIED, Set.of());

        assertThat(assessment).isEqualTo(new Assessment.Withheld(new Siret("12345678900012")));
    }

    static Stream<Arguments> exclusions() {
        return Stream.of(
                Arguments.of(
                        retained().activities("64.20Z", "64.21Y"),
                        ExclusionReason.EXCLUDED_ACTIVITY),
                Arguments.of(retained().activities(null, null), ExclusionReason.EXCLUDED_ACTIVITY),
                Arguments.of(
                        retained().namedAfterPerson("JEANNE FICTIVE").signs(),
                        ExclusionReason.NO_SIGN),
                Arguments.of(retained().address(" "), ExclusionReason.INCOMPLETE),
                Arguments.of(retained().postalCode(null), ExclusionReason.INCOMPLETE),
                Arguments.of(retained().postalCode("2100"), ExclusionReason.INCOMPLETE),
                Arguments.of(retained().postalCode("[NON-DIFFUSIBLE]"), ExclusionReason.INCOMPLETE),
                Arguments.of(retained().communeCode(null), ExclusionReason.INCOMPLETE),
                Arguments.of(retained().signs("N".repeat(256)), ExclusionReason.INCOMPLETE),
                Arguments.of(retained().companyName("S".repeat(256)), ExclusionReason.INCOMPLETE),
                Arguments.of(retained().address("A".repeat(501)), ExclusionReason.INCOMPLETE),
                Arguments.of(retained().signs().companyName(null), ExclusionReason.INCOMPLETE));
    }

    @ParameterizedTest
    @MethodSource("exclusions")
    void establishmentFailingARuleIsExcludedWithItsReason(
            TestDirectoryEstablishment establishment, ExclusionReason reason) {
        Assessment assessment = establishment.build().assess(CLASSIFIED, Set.of());

        assertThat(assessment)
                .isEqualTo(new Assessment.Excluded(new Siret("12345678900012"), reason));
    }

    @Test
    void naf2025CodeClassifiesTheEstablishmentEvenWhenItsNafRev2CodeIsClassified() {
        Assessment assessment =
                retained().activities("56.10A", "64.21Y").build().assess(CLASSIFIED, Set.of());

        assertThat(assessment)
                .isEqualTo(
                        new Assessment.Excluded(
                                new Siret("12345678900012"), ExclusionReason.EXCLUDED_ACTIVITY));
    }

    @Test
    void nafRev2CodeClassifiesTheEstablishmentWithoutNaf2025Code() {
        Establishment establishment = retainedFrom(retained().activities("56.10A", null));

        assertThat(establishment.activity())
                .isEqualTo(new ActivityCode(Nomenclature.NAF_REV2, "56.10A"));
    }

    @Test
    void namedAfterPersonIsNamedAfterItsSignNeverAfterThePerson() {
        Establishment establishment =
                retainedFrom(
                        retained().namedAfterPerson("JEANNE FICTIVE").signs("", "AU PAIN FICTIF"));

        assertThat(establishment.name()).isEqualTo("AU PAIN FICTIF");
        assertThat(establishment.companyName()).isEqualTo("AU PAIN FICTIF");
    }

    @Test
    void namedAfterPersonWithoutSignIsNamedAfterItsTradeName() {
        Establishment establishment =
                retainedFrom(
                        retained()
                                .namedAfterPerson("JEANNE FICTIVE")
                                .signs()
                                .tradeName("CHEZ JEANNE FICTIF"));

        assertThat(establishment.companyName()).isEqualTo("CHEZ JEANNE FICTIF");
    }

    @Test
    void companyWithoutSignNorTradeNameIsNamedAfterTheCompany() {
        Establishment establishment = retainedFrom(retained().signs());

        assertThat(establishment.name()).isEqualTo("SOCIETE FICTIVE");
    }

    @Test
    void establishmentWithoutPositionIsRetainedWithoutOne() {
        Establishment establishment = retainedFrom(retained().position(null));

        assertThat(establishment.position()).isNull();
    }

    @Test
    void malformedNaf2025CodeLeavesTheNafRev2CodeToClassify() {
        Establishment establishment = retainedFrom(retained().activities("56.10A", "5611G"));

        assertThat(establishment.activity())
                .isEqualTo(new ActivityCode(Nomenclature.NAF_REV2, "56.10A"));
    }

    @Test
    void unknownCompanyCategoryIsDropped() {
        Establishment establishment = retainedFrom(retained().companyCategory("TPE"));

        assertThat(establishment.companyCategory()).isNull();
    }

    @Test
    void declaredTrainingOrganizationIsOfTheTrainingType() {
        Assessment assessment =
                retained().build().assess(CLASSIFIED, Set.of(new Siret("12345678900012")));

        Establishment establishment = ((Assessment.Retained) assessment).establishment();
        assertThat(establishment.companyType()).isEqualTo(CompanyType.TRAINING);
    }

    @Test
    void nulCharactersThatTheDatabaseRefusesAreRemoved() {
        Establishment establishment = retainedFrom(retained().signs("LE BIS\0TROT"));

        assertThat(establishment.name()).isEqualTo("LE BISTROT");
    }

    @Test
    void headcountWithoutEmployeeInTheReferenceYearHasNoRange() {
        Establishment establishment = retainedFrom(retained().headcountRange("NN"));

        assertThat(establishment.headcountRange()).isNull();
    }

    private static Establishment retainedFrom(TestDirectoryEstablishment establishment) {
        var assessment = establishment.build().assess(CLASSIFIED, Set.of());
        return ((Assessment.Retained) assessment).establishment();
    }
}
