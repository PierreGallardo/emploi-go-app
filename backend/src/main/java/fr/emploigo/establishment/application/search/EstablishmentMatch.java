package fr.emploigo.establishment.application.search;

import fr.emploigo.establishment.domain.DisplayName;
import fr.emploigo.establishment.domain.Sector;
import fr.emploigo.establishment.domain.Siret;

/** An establishment suggested in « Quoi ? ». */
public record EstablishmentMatch(Siret siret, String name, String companyName, Sector sector) {
    EstablishmentMatch shown() {
        return new EstablishmentMatch(
                siret, DisplayName.ofEstablishment(name, companyName), companyName, sector);
    }
}
