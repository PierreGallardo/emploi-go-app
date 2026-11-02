package fr.emploigo.establishment.infrastructure.synchronization;

import fr.emploigo.establishment.application.synchronization.AddressFileClient;
import fr.emploigo.establishment.domain.Address;
import fr.emploigo.establishment.domain.HouseNumber;
import fr.emploigo.establishment.domain.Position;
import fr.emploigo.establishment.domain.Street;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/**
 * The addresses of the Base Adresse Nationale (Licence Ouverte 2.0) for the Côte-d'Or: a gzipped
 * CSV table, one line per address, separated by semicolons. A lieu-dit, numbered 99999, is no
 * address.
 */
class BanAddressFileClient implements AddressFileClient {
    private final URI url;
    private final FileDownloader downloader;
    private final UnpackedLimits limits;

    BanAddressFileClient(URI url, FileDownloader downloader, UnpackedLimits limits) {
        this.url = url;
        this.downloader = downloader;
        this.limits = limits;
    }

    @Override
    public Result fetch(Set<String> communeCodes, Optional<Instant> knownPublication) {
        return switch (downloader.download(url, knownPublication)) {
            case FileDownloader.Download.Failed failed -> new Result.Unavailable(failed.cause());
            case FileDownloader.Download.NotModified notModified -> new Result.Unchanged();
            case FileDownloader.Download.File file -> read(file, communeCodes);
        };
    }

    private Result read(FileDownloader.Download.File file, Set<String> communeCodes) {
        List<Address> addresses = new ArrayList<>();
        try (var text =
                new InputStreamReader(
                        new CappedInputStream(
                                new GZIPInputStream(new ByteArrayInputStream(file.body())),
                                limits.maxBytes()),
                        StandardCharsets.UTF_8)) {
            CsvReader.read(
                    text,
                    ';',
                    limits,
                    row -> {
                        if (communeCodes.contains(row.get("code_insee")))
                            address(row).ifPresent(addresses::add);
                    });
        } catch (IOException error) {
            return new Result.Unavailable("fichier illisible : " + error);
        }
        return new Result.Published(file.publishedAt(), addresses);
    }

    private static Optional<Address> address(CsvReader.Row row) {
        String street = row.get("nom_voie");
        if (street.isEmpty() || street.length() > Street.MAX_NAME_LENGTH) return Optional.empty();
        return Position.parse(row.get("lon"), row.get("lat"))
                .flatMap(position -> HouseNumber.parse(row.get("numero"), row.get("rep"), position))
                .map(number -> new Address(row.get("code_insee"), street, number));
    }
}
