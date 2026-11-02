package fr.emploigo.auth.api.registration;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.Size;

public record ResendConfirmationRequest(
        @Size(max = 64)
                @Schema(
                        description =
                                "Identifiant de l'inscription, donné par l'en-tête Registration-Id"
                                        + " de l'inscription",
                        requiredMode = Schema.RequiredMode.REQUIRED)
                String registration) {}
