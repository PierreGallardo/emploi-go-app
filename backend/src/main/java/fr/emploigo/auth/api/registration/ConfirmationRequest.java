package fr.emploigo.auth.api.registration;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ConfirmationRequest(
        @NotBlank @Size(max = 32) String code,
        @Size(max = 64)
                @Schema(
                        description =
                                "Identifiant de l'inscription, donné par l'en-tête Registration-Id"
                                        + " de l'inscription",
                        requiredMode = Schema.RequiredMode.REQUIRED)
                String registration) {
    @Override
    public String toString() {
        return "ConfirmationRequest[redacted]";
    }
}
