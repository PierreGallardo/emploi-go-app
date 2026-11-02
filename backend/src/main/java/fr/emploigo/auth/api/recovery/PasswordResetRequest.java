package fr.emploigo.auth.api.recovery;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PasswordResetRequest(
        @NotBlank @Size(max = 80) @Schema(maxLength = 80) String token,
        @NotBlank @Size(min = 8, max = 128) @Schema(minLength = 8, maxLength = 128) String password,
        @NotBlank @Size(max = 128) @Schema(maxLength = 128) String confirmation) {
    @Override
    public String toString() {
        return "PasswordResetRequest[redacted]";
    }
}
