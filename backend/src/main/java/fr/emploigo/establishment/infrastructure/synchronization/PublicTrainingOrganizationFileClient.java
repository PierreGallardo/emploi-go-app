package fr.emploigo.establishment.infrastructure.synchronization;

import fr.emploigo.establishment.application.synchronization.TrainingOrganizationFileClient;
import fr.emploigo.establishment.domain.Siret;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * The public list of the training organisations (Licence Ouverte 2.0): a CSV table separated by
 * semicolons, one line per organisation, with the SIRET of the establishment that declared it.
 */
class PublicTrainingOrganizationFileClient implements TrainingOrganizationFileClient {
    private final URI url;
    private final FileDownloader downloader;
    private final UnpackedLimits limits;

    PublicTrainingOrganizationFileClient(
            URI url, FileDownloader downloader, UnpackedLimits limits) {
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
        Set<Siret> sirets = new HashSet<>();
        try (var text =
                new InputStreamReader(
                        new CappedInputStream(
                                new ByteArrayInputStream(file.body()), limits.maxBytes()),
                        StandardCharsets.UTF_8)) {
            CsvReader.read(
                    text,
                    ';',
                    limits,
                    row -> Siret.of(row.get("siretEtablissementDeclarant")).ifPresent(sirets::add));
        } catch (IOException error) {
            return new Result.Unavailable("fichier illisible : " + error);
        }
        return new Result.Published(file.publishedAt(), sirets);
    }
}
