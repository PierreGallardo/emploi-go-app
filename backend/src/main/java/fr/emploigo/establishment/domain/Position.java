package fr.emploigo.establishment.domain;

import java.util.Optional;

/** A point on the map, in WGS 84 degrees. */
public record Position(double longitude, double latitude) {
    private static final double ECCENTRICITY = 0.0818191910428158;
    private static final double LAMBERT_N = 0.7256077650532670;
    private static final double LAMBERT_C = 11_754_255.426096;
    private static final double LAMBERT_EAST_ORIGIN = 700_000;
    private static final double LAMBERT_NORTH_ORIGIN = 12_655_612.049876;

    public Position {
        if (!isPosition(longitude, latitude))
            throw new IllegalArgumentException("Not a position: " + longitude + ", " + latitude);
    }

    /** The position of two decimal numbers, empty when they are absent or not one. */
    public static Optional<Position> parse(String longitude, String latitude) {
        if (longitude == null || latitude == null) return Optional.empty();
        try {
            double x = Double.parseDouble(longitude);
            double y = Double.parseDouble(latitude);
            return isPosition(x, y) ? Optional.of(new Position(x, y)) : Optional.empty();
        } catch (NumberFormatException unreadable) {
            return Optional.empty();
        }
    }

    /**
     * The position of a point in metric Lambert 93 (EPSG:2154), empty when the coordinates are
     * absent, not numbers, or outside France.
     */
    public static Optional<Position> parseLambert93(String east, String north) {
        if (east == null || north == null) return Optional.empty();
        double x;
        double y;
        try {
            x = Double.parseDouble(east);
            y = Double.parseDouble(north);
        } catch (NumberFormatException unreadable) {
            return Optional.empty();
        }
        // Inverse of the conic projection, as the IGN gives it (ALG0004), on the GRS80 ellipsoid.
        double dx = x - LAMBERT_EAST_ORIGIN;
        double dy = LAMBERT_NORTH_ORIGIN - y;
        double radius = Math.hypot(dx, dy);
        double isometricLatitude = -Math.log(radius / LAMBERT_C) / LAMBERT_N;
        double latitude = 2 * Math.atan(Math.exp(isometricLatitude)) - Math.PI / 2;
        for (int step = 0; step < 10; step++) {
            double sine = ECCENTRICITY * Math.sin(latitude);
            latitude =
                    2
                                    * Math.atan(
                                            Math.pow(
                                                            (1 + sine) / (1 - sine),
                                                            ECCENTRICITY / 2)
                                                    * Math.exp(isometricLatitude))
                            - Math.PI / 2;
        }
        double longitude = Math.toRadians(3) + Math.atan2(dx, dy) / LAMBERT_N;
        double degreesLongitude = Math.toDegrees(longitude);
        double degreesLatitude = Math.toDegrees(latitude);
        return isFrance(degreesLongitude, degreesLatitude)
                ? Optional.of(new Position(degreesLongitude, degreesLatitude))
                : Optional.empty();
    }

    private static boolean isFrance(double longitude, double latitude) {
        return longitude >= -10 && longitude <= 12 && latitude >= 41 && latitude <= 52;
    }

    private static boolean isPosition(double longitude, double latitude) {
        return longitude >= -180 && longitude <= 180 && latitude >= -90 && latitude <= 90;
    }

    /**
     * The distance in meters to another point, close enough at the scale of a town: the earth is
     * taken as flat around the middle of the two points.
     */
    public double metersTo(Position other) {
        double middleLatitude = Math.toRadians((latitude + other.latitude) / 2);
        double east = (longitude - other.longitude) * 111_320 * Math.cos(middleLatitude);
        double north = (latitude - other.latitude) * 110_540;
        return Math.hypot(east, north);
    }
}
