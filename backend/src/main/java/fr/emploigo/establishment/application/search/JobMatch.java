package fr.emploigo.establishment.application.search;

import fr.emploigo.establishment.domain.Sector;

/** A job suggested in « Quoi ? », under the sector where most of its establishments are. */
public record JobMatch(String label, Sector sector) {}
