package fr.emploigo.establishment.api.search;

import fr.emploigo.config.ApiOnly;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.Duration;

/**
 * Serves the photos of the establishments from the folder of the server, by the file name the
 * database keeps; a photo is replaced under a new name, so a browser may keep one for a day.
 */
@Configuration(proxyBeanMethods = false)
@ApiOnly
@EnableConfigurationProperties(EstablishmentImagesProperties.class)
class EstablishmentImagesConfiguration implements WebMvcConfigurer {
    static final String PATH = "/api/establishment-images/";

    private final EstablishmentImagesProperties properties;

    EstablishmentImagesConfiguration(EstablishmentImagesProperties properties) {
        this.properties = properties;
    }

    /** The address of a photo, null without one. */
    static String url(String imageFile) {
        return imageFile == null ? null : PATH + imageFile;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // A location is a folder only with its final slash, which a missing folder's URI lacks.
        String folder = properties.directory().toAbsolutePath().toUri().toString();
        registry.addResourceHandler(PATH + "*")
                .addResourceLocations(folder.endsWith("/") ? folder : folder + "/")
                .setCacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePrivate());
    }
}
