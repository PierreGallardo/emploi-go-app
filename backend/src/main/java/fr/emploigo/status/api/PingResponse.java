package fr.emploigo.status.api;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "PingResponse")
public record PingResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = "ok")
                String status) {}
