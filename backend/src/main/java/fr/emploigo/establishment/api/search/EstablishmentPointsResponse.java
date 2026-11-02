package fr.emploigo.establishment.api.search;

import fr.emploigo.establishment.application.search.EstablishmentPoint;
import fr.emploigo.establishment.domain.Sector;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

public record EstablishmentPointsResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
                List<EstablishmentPointResponse> items) {
    public record EstablishmentPointResponse(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String siret,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) double longitude,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) double latitude,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Sector sector) {}

    static EstablishmentPointsResponse from(List<EstablishmentPoint> points) {
        return new EstablishmentPointsResponse(
                points.stream()
                        .map(
                                point ->
                                        new EstablishmentPointResponse(
                                                point.siret().value(),
                                                point.position().longitude(),
                                                point.position().latitude(),
                                                point.sector()))
                        .toList());
    }
}
