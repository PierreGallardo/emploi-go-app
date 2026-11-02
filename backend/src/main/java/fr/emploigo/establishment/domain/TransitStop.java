package fr.emploigo.establishment.domain;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A stop of the Divia network as the map shows it: its platforms of the same name, one per
 * direction, in one place. Its lines are in the order of the network.
 */
public record TransitStop(String name, Position position, List<TransitLine> lines) {
    public static final int MAX_NAME_LENGTH = 100;

    /** The platforms of one stop lie closer than this to one another. */
    static final double PLATFORMS_DISTANCE_METERS = 150;

    public TransitStop {
        Objects.requireNonNull(position);
        if (name == null || name.isBlank() || name.length() > MAX_NAME_LENGTH)
            throw new IllegalArgumentException("Not a stop name: " + name);
        if (lines.isEmpty()) throw new IllegalArgumentException("A stop is served by a line.");
        lines = lines.stream().sorted(TransitLine.ORDER).toList();
    }

    /** A stop where a tram calls is a tram stop, whatever buses also call at it. */
    public TransitMode mode() {
        return lines.stream().anyMatch(line -> line.mode() == TransitMode.TRAM)
                ? TransitMode.TRAM
                : TransitMode.BUS;
    }

    /**
     * The stops of the platforms: a platform joins a group of the same name that has a platform
     * close to it. A stop lies in the middle of its platforms and has each of their lines once, by
     * name.
     */
    public static List<TransitStop> group(Collection<TransitPlatform> platforms) {
        List<List<TransitPlatform>> groups = new ArrayList<>();
        for (TransitPlatform platform : platforms) {
            groups.stream()
                    .filter(group -> isStopOf(group, platform))
                    .findFirst()
                    .ifPresentOrElse(
                            group -> group.add(platform),
                            () -> groups.add(new ArrayList<>(List.of(platform))));
        }
        return groups.stream().map(TransitStop::of).toList();
    }

    private static boolean isStopOf(List<TransitPlatform> group, TransitPlatform platform) {
        return group.getFirst().name().equals(platform.name())
                && group.stream()
                        .anyMatch(
                                other ->
                                        other.position().metersTo(platform.position())
                                                < PLATFORMS_DISTANCE_METERS);
    }

    private static TransitStop of(List<TransitPlatform> platforms) {
        Map<String, TransitLine> lines = new LinkedHashMap<>();
        platforms.stream()
                .flatMap(platform -> platform.lines().stream().sorted(TransitLine.ORDER))
                .forEach(line -> lines.putIfAbsent(line.name(), line));
        return new TransitStop(
                platforms.getFirst().name(),
                new Position(
                        platforms.stream()
                                .mapToDouble(platform -> platform.position().longitude())
                                .average()
                                .orElseThrow(),
                        platforms.stream()
                                .mapToDouble(platform -> platform.position().latitude())
                                .average()
                                .orElseThrow()),
                List.copyOf(lines.values()));
    }
}
