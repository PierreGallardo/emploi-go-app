package fr.emploigo.auth.api.recovery;

import io.swagger.v3.oas.annotations.media.Schema;

public record PasswordResetResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean changed) {}
