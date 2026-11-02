package fr.emploigo.establishment.application.search;

import fr.emploigo.establishment.domain.DisplayName;
import fr.emploigo.establishment.domain.Position;
import fr.emploigo.establishment.domain.Sector;
import fr.emploigo.establishment.domain.Siret;

import java.util.List;

/**
 * An establishment of a list of the map. {@code position}, {@code description}, {@code imageFile}
 * and {@code distanceMeters} (from the place searched around) may be absent; {@code jobs} are the
 * first jobs done in its activity.
 */
public record EstablishmentSummary(
        Siret siret,
        String name,
        String companyName,
        Sector sector,
        String commune,
        Position position,
        String description,
        String imageFile,
        List<String> jobs,
        Integer distanceMeters) {
    public EstablishmentSummary {
        jobs = List.copyOf(jobs);
    }

    /** The summary with its name as shown. */
    EstablishmentSummary shown() {
        return new EstablishmentSummary(
                siret,
                DisplayName.ofEstablishment(name, companyName),
                companyName,
                sector,
                commune,
                position,
                description,
                imageFile,
                jobs,
                distanceMeters);
    }
}
