package fr.emploigo.establishment.application.search;

import fr.emploigo.establishment.domain.TransitMode;

/** The Divia stop closest to an establishment, as the crow flies. */
public record NearestStop(String name, TransitMode mode, int distanceMeters) {}
