package fr.emploigo.establishment.infrastructure.synchronization;

import fr.emploigo.establishment.application.synchronization.TransitFeedClient;
import fr.emploigo.establishment.domain.Position;
import fr.emploigo.establishment.domain.TransitLine;
import fr.emploigo.establishment.domain.TransitMode;
import fr.emploigo.establishment.domain.TransitPlatform;
import fr.emploigo.establishment.domain.TransitStop;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * The GTFS feed of DiviaMobilités (Licence Ouverte 2.0): a zip archive of CSV tables. A platform is
 * a stop of {@code stops.txt} that a trip of {@code stop_times.txt} calls at.
 */
class GtfsTransitFeedClient implements TransitFeedClient {
    private static final String TRAM_ROUTE_TYPE = "0";

    private final URI url;
    private final FileDownloader downloader;
    private final UnpackedLimits limits;

    GtfsTransitFeedClient(URI url, FileDownloader downloader, UnpackedLimits limits) {
        this.url = url;
        this.downloader = downloader;
        this.limits = limits;
    }

    @Override
    public Result fetch(Optional<Instant> knownPublication) {
        return switch (downloader.download(url, knownPublication)) {
            case FileDownloader.Download.Failed failed -> new Result.Unavailable(failed.cause());
            case FileDownloader.Download.NotModified notModified -> new Result.Unchanged();
            case FileDownloader.Download.File file -> read(file);
        };
    }

    private Result read(FileDownloader.Download.File file) {
        try {
            Map<String, TransitLine> lineOfRoute = new HashMap<>();
            table(
                    file.body(),
                    "routes.txt",
                    route -> line(route).ifPresent(l -> lineOfRoute.put(route.get("route_id"), l)));
            Map<String, TransitLine> lineOfTrip = new HashMap<>();
            table(
                    file.body(),
                    "trips.txt",
                    trip -> {
                        TransitLine line = lineOfRoute.get(trip.get("route_id"));
                        if (line != null) lineOfTrip.put(trip.get("trip_id"), line);
                    });
            Map<String, Set<TransitLine>> linesOfStop = new HashMap<>();
            table(
                    file.body(),
                    "stop_times.txt",
                    call -> {
                        TransitLine line = lineOfTrip.get(call.get("trip_id"));
                        if (line != null)
                            linesOfStop
                                    .computeIfAbsent(call.get("stop_id"), stop -> new HashSet<>())
                                    .add(line);
                    });
            List<TransitPlatform> platforms = new ArrayList<>();
            table(
                    file.body(),
                    "stops.txt",
                    stop -> {
                        Set<TransitLine> lines = linesOfStop.get(stop.get("stop_id"));
                        Optional<Position> position =
                                Position.parse(stop.get("stop_lon"), stop.get("stop_lat"));
                        String name = stop.get("stop_name");
                        if (lines != null
                                && position.isPresent()
                                && !name.isEmpty()
                                && name.length() <= TransitStop.MAX_NAME_LENGTH)
                            platforms.add(new TransitPlatform(name, position.get(), lines));
                    });
            return new Result.Published(file.publishedAt(), platforms);
        } catch (IOException error) {
            return new Result.Unavailable("flux illisible : " + error);
        }
    }

    /** The line of a route, by its short name, or its long name when it has none. */
    private static Optional<TransitLine> line(CsvReader.Row route) {
        String name = route.get("route_short_name");
        if (name.isEmpty()) name = route.get("route_long_name");
        if (name.isEmpty() || name.length() > TransitLine.MAX_NAME_LENGTH) return Optional.empty();
        return Optional.of(
                new TransitLine(
                        name,
                        TRAM_ROUTE_TYPE.equals(route.get("route_type"))
                                ? TransitMode.TRAM
                                : TransitMode.BUS));
    }

    /**
     * Reads one table of the archive, which is read again from the start for each table. The
     * entries before it are unpacked through the same limit as the table.
     */
    private void table(byte[] archive, String name, Consumer<CsvReader.Row> row)
            throws IOException {
        try (var zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            var unpacked = new CappedInputStream(zip, limits.maxBytes());
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (entry.getName().equals(name)) {
                    CsvReader.read(
                            new InputStreamReader(unpacked, StandardCharsets.UTF_8),
                            ',',
                            limits,
                            row);
                    return;
                }
                unpacked.transferTo(OutputStream.nullOutputStream());
            }
        }
        throw new IOException(name + " absent de l'archive");
    }
}
