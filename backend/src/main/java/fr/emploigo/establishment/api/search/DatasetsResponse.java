package fr.emploigo.establishment.api.search;

import fr.emploigo.establishment.application.search.Datasets;
import fr.emploigo.establishment.domain.TransitMode;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

public record DatasetsResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<TransitStopResponse> stops,
        @Schema(
                        requiredMode = Schema.RequiredMode.REQUIRED,
                        nullable = true,
                        description = "Publication du GTFS de DiviaMobilités")
                Instant transitPublishedAt,
        @Schema(
                        requiredMode = Schema.RequiredMode.REQUIRED,
                        nullable = true,
                        description = "Publication de la Base Adresse Nationale")
                Instant addressesPublishedAt) {
    public record TransitStopResponse(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) TransitMode mode,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) double longitude,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) double latitude) {}

    static DatasetsResponse from(Datasets datasets) {
        return new DatasetsResponse(
                datasets.stops().stream()
                        .map(
                                stop ->
                                        new TransitStopResponse(
                                                stop.name(),
                                                stop.mode(),
                                                stop.position().longitude(),
                                                stop.position().latitude()))
                        .toList(),
                datasets.transitPublishedAt(),
                datasets.addressesPublishedAt());
    }
}
