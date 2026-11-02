package fr.emploigo.auth.api.recovery;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RecoveryRequest(
        @NotBlank @Email @Size(max = 320) @Schema(maxLength = 320) String email) {
    @Override
    public String toString() {
        return "RecoveryRequest[redacted]";
    }
}
