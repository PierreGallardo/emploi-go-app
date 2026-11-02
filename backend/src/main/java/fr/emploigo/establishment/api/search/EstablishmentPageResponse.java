package fr.emploigo.establishment.api.search;

import fr.emploigo.establishment.application.search.EstablishmentPage;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

public record EstablishmentPageResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
                List<EstablishmentSummaryResponse> items,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long total,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int page,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int pageSize) {
    static EstablishmentPageResponse from(EstablishmentPage found, int page, int pageSize) {
        return new EstablishmentPageResponse(
                found.items().stream().map(EstablishmentSummaryResponse::from).toList(),
                found.total(),
                page,
                pageSize);
    }
}
