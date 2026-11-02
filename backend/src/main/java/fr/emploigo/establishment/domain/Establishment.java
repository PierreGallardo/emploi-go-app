package fr.emploigo.establishment.domain;

import java.time.LocalDate;

/**
 * An establishment as the map shows it, from the data of the State directory. {@code position},
 * {@code headcountRange}, {@code companyCategory} and {@code companyCreatedOn} may be absent.
 */
public record Establishment(
        Siret siret,
        String name,
        String companyName,
        String address,
        String communeCode,
        Position position,
        ActivityCode activity,
        CompanyType companyType,
        HeadcountRange headcountRange,
        String companyCategory,
        LocalDate companyCreatedOn,
        boolean ess) {}
