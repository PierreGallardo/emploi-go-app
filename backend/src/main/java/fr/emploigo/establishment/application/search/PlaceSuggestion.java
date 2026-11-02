package fr.emploigo.establishment.application.search;

import fr.emploigo.establishment.domain.Position;
import fr.emploigo.establishment.domain.TransitMode;

/**
 * A place suggested in « Où ? ». {@code detail} is the commune of an address or a street, the first
 * lines of a stop; {@code mode} is the mode of a stop.
 */
public record PlaceSuggestion(
        Kind kind, String label, String detail, TransitMode mode, Position position) {
    public enum Kind {
        COMMUNE,
        STREET,
        STOP
    }
}
