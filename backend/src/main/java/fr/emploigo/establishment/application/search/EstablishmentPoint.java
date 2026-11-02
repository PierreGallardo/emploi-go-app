package fr.emploigo.establishment.application.search;

import fr.emploigo.establishment.domain.Position;
import fr.emploigo.establishment.domain.Sector;
import fr.emploigo.establishment.domain.Siret;

/** An establishment as a point of the map. */
public record EstablishmentPoint(Siret siret, Position position, Sector sector) {}
