package fr.emploigo.establishment.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * A fictitious establishment of the directory that the import retains, to change field by field.
 */
public final class TestDirectoryEstablishment {
    public static final ActivityCode RESTAURANT = new ActivityCode(Nomenclature.NAF_2025, "56.11G");
    public static final Set<ActivityCode> CLASSIFIED =
            Set.of(RESTAURANT, new ActivityCode(Nomenclature.NAF_REV2, "56.10A"));

    private String siret = "12345678900012";
    private boolean diffusible = true;
    private boolean namedAfterPerson;
    private String companyName = "SOCIETE FICTIVE";
    private List<String> signs = List.of("LE BISTROT FICTIF");
    private String tradeName;
    private String address = "1 RUE FICTIVE 21000 DIJON";
    private String postalCode = "21000";
    private String communeCode = "21231";
    private Position position = new Position(5.04, 47.32);
    private String activityNafRev2 = "56.10A";
    private String activityNaf2025 = "56.11G";
    private String headcountRange = "11";
    private String companyCategory = "PME";

    public static TestDirectoryEstablishment retained() {
        return new TestDirectoryEstablishment();
    }

    public TestDirectoryEstablishment siret(String siret) {
        this.siret = siret;
        return this;
    }

    public TestDirectoryEstablishment diffusible(boolean diffusible) {
        this.diffusible = diffusible;
        return this;
    }

    public TestDirectoryEstablishment namedAfterPerson(String personName) {
        this.namedAfterPerson = true;
        this.companyName = personName;
        return this;
    }

    public TestDirectoryEstablishment companyName(String companyName) {
        this.companyName = companyName;
        return this;
    }

    public TestDirectoryEstablishment signs(String... signs) {
        this.signs = List.of(signs);
        return this;
    }

    public TestDirectoryEstablishment tradeName(String tradeName) {
        this.tradeName = tradeName;
        return this;
    }

    public TestDirectoryEstablishment address(String address) {
        this.address = address;
        return this;
    }

    public TestDirectoryEstablishment postalCode(String postalCode) {
        this.postalCode = postalCode;
        return this;
    }

    public TestDirectoryEstablishment communeCode(String communeCode) {
        this.communeCode = communeCode;
        return this;
    }

    public TestDirectoryEstablishment companyCategory(String companyCategory) {
        this.companyCategory = companyCategory;
        return this;
    }

    public TestDirectoryEstablishment position(Position position) {
        this.position = position;
        return this;
    }

    public TestDirectoryEstablishment activities(String nafRev2, String naf2025) {
        this.activityNafRev2 = nafRev2;
        this.activityNaf2025 = naf2025;
        return this;
    }

    public TestDirectoryEstablishment headcountRange(String headcountRange) {
        this.headcountRange = headcountRange;
        return this;
    }

    public DirectoryEstablishment build() {
        return new DirectoryEstablishment(
                new Siret(siret),
                diffusible,
                namedAfterPerson,
                companyName,
                signs,
                tradeName,
                address,
                postalCode,
                communeCode,
                position,
                activityNafRev2,
                activityNaf2025,
                headcountRange,
                companyCategory,
                LocalDate.of(2011, 3, 1),
                true);
    }
}
