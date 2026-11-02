package fr.emploigo.establishment.application.search;

import fr.emploigo.establishment.domain.Position;

/** A number of a street, by its label (« 6 bis »). */
public record NumberMatch(int streetId, String label, Position position) {}
