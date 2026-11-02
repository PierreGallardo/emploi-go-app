package fr.emploigo.establishment.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * An establishment as the State directory describes it, with the data of its company. Any text and
 * the position may be absent, a text may be blank; {@code companyName} is absent for an individual
 * entrepreneur, whose company bears the name of a person.
 */
public record DirectoryEstablishment(
        Siret siret,
        boolean diffusible,
        boolean namedAfterPerson,
        String companyName,
        List<String> signs,
        String tradeName,
        String address,
        String postalCode,
        String communeCode,
        Position position,
        String activityNafRev2,
        String activityNaf2025,
        String headcountRange,
        String companyCategory,
        LocalDate companyCreatedOn,
        boolean ess) {
    private static final Pattern FIVE_DIGITS = Pattern.compile("[0-9]{5}");
    private static final int NAME_LIMIT = 255;
    private static final int ADDRESS_LIMIT = 500;
    private static final Set<String> COMPANY_CATEGORIES = Set.of("PME", "ETI", "GE");

    public DirectoryEstablishment {
        signs = signs == null ? List.of() : List.copyOf(signs);
    }

    /**
     * Applies the rules of the import, in this order: diffusion, activity in the
     * allow list ({@code classified}), a name that is not a person's, then an address the database
     * accepts. The establishments in {@code trainers} are declared training organisations.
     */
    public Assessment assess(Set<ActivityCode> classified, Set<Siret> trainers) {
        if (!diffusible) return new Assessment.Withheld(siret);
        Optional<ActivityCode> activity = activity().filter(classified::contains);
        if (activity.isEmpty())
            return new Assessment.Excluded(siret, ExclusionReason.EXCLUDED_ACTIVITY);
        Optional<String> name = name();
        if (name.isEmpty() && namedAfterPerson)
            return new Assessment.Excluded(siret, ExclusionReason.NO_SIGN);
        String company = namedAfterPerson ? name.orElse(null) : text(companyName).orElse(null);
        Optional<String> street = text(address);
        if (name.isEmpty()
                || name.get().length() > NAME_LIMIT
                || company != null && company.length() > NAME_LIMIT
                || street.isEmpty()
                || street.get().length() > ADDRESS_LIMIT
                || postalCode == null
                || !FIVE_DIGITS.matcher(postalCode).matches()
                || communeCode == null
                || !FIVE_DIGITS.matcher(communeCode).matches())
            return new Assessment.Excluded(siret, ExclusionReason.INCOMPLETE);
        return new Assessment.Retained(
                new Establishment(
                        siret,
                        name.get(),
                        company == null ? name.get() : company,
                        street.get(),
                        communeCode,
                        position,
                        activity.get(),
                        CompanyType.of(activity.get(), trainers.contains(siret)),
                        HeadcountRange.of(headcountRange).orElse(null),
                        companyCategory != null && COMPANY_CATEGORIES.contains(companyCategory)
                                ? companyCategory
                                : null,
                        companyCreatedOn,
                        ess));
    }

    /** The NAF 2025 code, which replaces the NAF rév. 2 one; a malformed code counts as absent. */
    private Optional<ActivityCode> activity() {
        return ActivityCode.of(Nomenclature.NAF_2025, activityNaf2025)
                .or(() -> ActivityCode.of(Nomenclature.NAF_REV2, activityNafRev2));
    }

    /** The sign, then the trade name, then the company name, never the name of a person. */
    private Optional<String> name() {
        return signs.stream()
                .map(DirectoryEstablishment::text)
                .flatMap(Optional::stream)
                .findFirst()
                .or(() -> text(tradeName))
                .or(() -> namedAfterPerson ? Optional.empty() : text(companyName));
    }

    /** A text without its edge spaces, empty when blank; PostgreSQL refuses the NUL character. */
    private static Optional<String> text(String value) {
        if (value == null) return Optional.empty();
        String text = value.replace("\0", "").strip();
        return text.isEmpty() ? Optional.empty() : Optional.of(text);
    }
}
