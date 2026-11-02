package fr.emploigo.auth.api.google;

import io.swagger.v3.oas.annotations.media.Schema;

public record GoogleLinkResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean changed) {}
