package fr.emploigo.establishment.api.search;

import fr.emploigo.establishment.application.search.EstablishmentSummary;
import fr.emploigo.establishment.domain.Sector;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

public record EstablishmentSummaryResponse(
        @Schema(
                        requiredMode = Schema.RequiredMode.REQUIRED,
                        description = "SIRET, identifiant de la fiche",
                        example = "12345678900012")
                String siret,
        @Schema(
                        requiredMode = Schema.RequiredMode.REQUIRED,
                        description = "Enseigne, sinon raison sociale, mise en forme")
                String name,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Sector sector,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String commune,
        @Schema(
                        requiredMode = Schema.RequiredMode.REQUIRED,
                        nullable = true,
                        description = "WGS 84 ; null si l'établissement n'est pas localisé")
                Double longitude,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) Double latitude,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) String description,
        @Schema(
                        requiredMode = Schema.RequiredMode.REQUIRED,
                        nullable = true,
                        description =
                                "Photo de l'établissement ; null : montrer le visuel du secteur")
                String imageUrl,
        @Schema(
                        requiredMode = Schema.RequiredMode.REQUIRED,
                        description = "Premiers métiers qui s'exercent dans son activité")
                List<String> jobs,
        @Schema(
                        requiredMode = Schema.RequiredMode.REQUIRED,
                        nullable = true,
                        description = "Distance au lieu cherché, en mètres")
                Integer distanceMeters) {
    static EstablishmentSummaryResponse from(EstablishmentSummary summary) {
        return new EstablishmentSummaryResponse(
                summary.siret().value(),
                summary.name(),
                summary.sector(),
                summary.commune(),
                summary.position() == null ? null : summary.position().longitude(),
                summary.position() == null ? null : summary.position().latitude(),
                summary.description(),
                EstablishmentImagesConfiguration.url(summary.imageFile()),
                summary.jobs(),
                summary.distanceMeters());
    }
}
