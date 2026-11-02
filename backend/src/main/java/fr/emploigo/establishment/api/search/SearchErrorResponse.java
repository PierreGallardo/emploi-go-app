package fr.emploigo.establishment.api.search;

import io.swagger.v3.oas.annotations.media.Schema;

public record SearchErrorResponse(
        @Schema(
                        requiredMode = Schema.RequiredMode.REQUIRED,
                        allowableValues = {
                            "INVALID_INPUT",
                            "UNAUTHENTICATED",
                            "FORBIDDEN",
                            "ESTABLISHMENT_NOT_FOUND",
                            "UNAVAILABLE"
                        })
                String code,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String message) {}
