package fr.emploigo.status.api;

import io.swagger.v3.oas.annotations.media.Schema;

public record PingErrorResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = "UNAVAILABLE")
                String code,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String message) {}
