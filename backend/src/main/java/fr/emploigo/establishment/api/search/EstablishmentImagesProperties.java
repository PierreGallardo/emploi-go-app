package fr.emploigo.establishment.api.search;

import fr.emploigo.config.ApiOnly;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

/** The folder of the server that holds the photos of the establishments. */
@ApiOnly
@ConfigurationProperties(prefix = "emploi-go.establishment-images", ignoreUnknownFields = false)
public record EstablishmentImagesProperties(Path directory) {
    public EstablishmentImagesProperties {
        if (directory == null)
            throw new IllegalArgumentException(
                    "emploi-go.establishment-images.directory is required");
    }
}
