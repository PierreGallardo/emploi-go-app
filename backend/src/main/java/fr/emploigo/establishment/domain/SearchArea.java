package fr.emploigo.establishment.domain;

import java.util.Objects;

/** The circle a youth searches in: the place chosen in « Où ? » and the radius of the filters. */
public record SearchArea(Position centre, int radiusKm) {
    public static final int MIN_RADIUS_KM = 1;
    // Around 6 km the circle fills the map at its widest: the map shows nothing farther.
    public static final int MAX_RADIUS_KM = 6;

    public SearchArea {
        Objects.requireNonNull(centre);
        if (radiusKm < MIN_RADIUS_KM || radiusKm > MAX_RADIUS_KM)
            throw new IllegalArgumentException("Not a radius of the map: " + radiusKm);
    }

    public int radiusMeters() {
        return radiusKm * 1000;
    }
}
