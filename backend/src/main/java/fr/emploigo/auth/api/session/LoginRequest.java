package fr.emploigo.auth.api.session;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record LoginRequest(
        @NotBlank @Size(max = 320) @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
                String identifier,
        @NotNull
                @Size(min = 1, max = 128)
                @Schema(
                        requiredMode = Schema.RequiredMode.REQUIRED,
                        accessMode = Schema.AccessMode.WRITE_ONLY)
                String password,
        // Optional: a frontend opened before this field sends none, and stays unchecked.
        @Schema(
                        description =
                                "Rester connecté sur ce navigateur pendant 30 jours ; sinon la"
                                        + " session s'arrête à sa fermeture ou après"
                                        + " l'inactivité",
                        defaultValue = "false")
                Boolean rememberMe) {
    public boolean remembered() {
        return Boolean.TRUE.equals(rememberMe);
    }

    @Override
    public String toString() {
        return "LoginRequest[redacted]";
    }
}
