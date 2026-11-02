package fr.emploigo.establishment.domain;

import java.util.Objects;
import java.util.Set;

/** A platform of the network, where the lines that call at it stop in one direction. */
public record TransitPlatform(String name, Position position, Set<TransitLine> lines) {
    public TransitPlatform {
        Objects.requireNonNull(position);
        if (name == null || name.isBlank())
            throw new IllegalArgumentException("A platform has a name.");
        if (lines.isEmpty()) throw new IllegalArgumentException("A platform is served by a line.");
        lines = Set.copyOf(lines);
    }
}
