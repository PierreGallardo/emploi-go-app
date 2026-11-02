package fr.emploigo.establishment.application.search;

import fr.emploigo.establishment.domain.DisplayName;
import fr.emploigo.establishment.domain.Position;
import fr.emploigo.establishment.domain.Sector;
import fr.emploigo.establishment.domain.Siret;

import java.util.List;

/**
 * The page of an establishment. The registers may leave out {@code headcountRange}, {@code
 * companyCategory} and {@code companyCreationYear}; we may not have written {@code description},
 * {@code imageFile} or {@code jobsUrl} yet. An establishment without a position has no {@code
 * nearestStop}.
 */
public record EstablishmentDetail(
        Siret siret,
        String name,
        String companyName,
        Sector sector,
        String activity,
        String address,
        String commune,
        Position position,
        String description,
        String imageFile,
        String jobsUrl,
        List<String> jobs,
        String headcountRange,
        String companyCategory,
        Integer companyCreationYear,
        boolean ess,
        NearestStop nearestStop) {
    public EstablishmentDetail {
        jobs = List.copyOf(jobs);
    }

    /** The page with its names and address as shown. */
    EstablishmentDetail shown() {
        return new EstablishmentDetail(
                siret,
                DisplayName.ofEstablishment(name, companyName),
                DisplayName.of(companyName),
                sector,
                activity,
                DisplayName.of(address),
                commune,
                position,
                description,
                imageFile,
                jobsUrl,
                jobs,
                headcountRange,
                companyCategory,
                companyCreationYear,
                ess,
                nearestStop);
    }
}
