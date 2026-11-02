package fr.emploigo.establishment.api.search;

import fr.emploigo.establishment.application.search.EstablishmentDetail;
import fr.emploigo.establishment.domain.Sector;
import fr.emploigo.establishment.domain.TransitMode;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

public record EstablishmentDetailResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String siret,
        @Schema(
                        requiredMode = Schema.RequiredMode.REQUIRED,
                        description = "Enseigne, sinon raison sociale, mise en forme")
                String name,
        @Schema(
                        requiredMode = Schema.RequiredMode.REQUIRED,
                        description = "Raison sociale, mise en forme")
                String companyName,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Sector sector,
        @Schema(
                        requiredMode = Schema.RequiredMode.REQUIRED,
                        description = "Libellé de l'activité principale (NAF)")
                String activity,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String address,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String commune,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) Double longitude,
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
                        nullable = true,
                        description = "Page de recrutement de l'entreprise")
                String jobsUrl,
        @Schema(
                        requiredMode = Schema.RequiredMode.REQUIRED,
                        description = "Métiers qui s'exercent dans son activité")
                List<String> jobs,
        @Schema(
                        requiredMode = Schema.RequiredMode.REQUIRED,
                        nullable = true,
                        description = "Tranche d'effectif INSEE de l'établissement",
                        example = "11")
                String headcountRange,
        @Schema(
                        requiredMode = Schema.RequiredMode.REQUIRED,
                        nullable = true,
                        allowableValues = {"PME", "ETI", "GE"})
                String companyCategory,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
                Integer companyCreationYear,
        @Schema(
                        requiredMode = Schema.RequiredMode.REQUIRED,
                        description = "Économie sociale et solidaire")
                boolean ess,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
                NearestStopResponse nearestStop) {
    public record NearestStopResponse(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) TransitMode mode,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int distanceMeters) {}

    static EstablishmentDetailResponse from(EstablishmentDetail detail) {
        return new EstablishmentDetailResponse(
                detail.siret().value(),
                detail.name(),
                detail.companyName(),
                detail.sector(),
                detail.activity(),
                detail.address(),
                detail.commune(),
                detail.position() == null ? null : detail.position().longitude(),
                detail.position() == null ? null : detail.position().latitude(),
                detail.description(),
                EstablishmentImagesConfiguration.url(detail.imageFile()),
                detail.jobsUrl(),
                detail.jobs(),
                detail.headcountRange(),
                detail.companyCategory(),
                detail.companyCreationYear(),
                detail.ess(),
                detail.nearestStop() == null
                        ? null
                        : new NearestStopResponse(
                                detail.nearestStop().name(),
                                detail.nearestStop().mode(),
                                detail.nearestStop().distanceMeters()));
    }
}
