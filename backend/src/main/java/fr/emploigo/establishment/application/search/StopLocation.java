package fr.emploigo.establishment.application.search;

import fr.emploigo.establishment.domain.Position;
import fr.emploigo.establishment.domain.TransitMode;

/** A Divia stop as the map draws it. */
public record StopLocation(String name, TransitMode mode, Position position) {}
