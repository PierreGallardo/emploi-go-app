package fr.emploigo.establishment.application.search;

import fr.emploigo.establishment.domain.Position;

/** A street of the Base Adresse Nationale, with the name of its commune. */
public record StreetMatch(int id, String name, String commune, Position position) {}
