package fr.emploigo.establishment.api.search;

import fr.emploigo.establishment.domain.CompanyType;
import fr.emploigo.establishment.domain.EstablishmentSize;
import fr.emploigo.establishment.domain.SearchArea;
import fr.emploigo.establishment.domain.SearchCriteria;
import fr.emploigo.establishment.domain.Sector;
import fr.emploigo.establishment.domain.Siret;

import io.swagger.v3.oas.annotations.Parameter;

import jakarta.validation.constraints.Size;

import java.util.Optional;
import java.util.Set;

/** The filters of the list and of the points, in the query of the URL. */
record FilterParameters(
        @Parameter(description = "Texte cherché dans le nom, la raison sociale et l'activité")
                @Size(max = SearchCriteria.MAX_TEXT_LENGTH)
                String q,
        @Parameter(description = "Libellé d'un métier ROME : les établissements qui l'exercent")
                @Size(max = SearchCriteria.MAX_JOB_LENGTH)
                String job,
        @Parameter(description = "Secteurs, au moins un") Set<Sector> sector,
        @Parameter(description = "Types d'employeur, au moins un") Set<CompanyType> companyType,
        @Parameter(description = "Tailles de l'établissement, au moins une")
                Set<EstablishmentSize> size,
        @Parameter(description = "Un arrêt Divia à " + SearchCriteria.TRANSIT_METERS + " m au plus")
                Boolean transit) {
    SearchCriteria criteria(Optional<SearchArea> area, Set<Siret> sirets) {
        return new SearchCriteria(
                q == null ? "" : q,
                Optional.ofNullable(job).map(String::strip).filter(label -> !label.isEmpty()),
                sector == null ? Set.of() : sector,
                companyType == null ? Set.of() : companyType,
                size == null ? Set.of() : size,
                Boolean.TRUE.equals(transit),
                area,
                sirets);
    }
}
