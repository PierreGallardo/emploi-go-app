package fr.emploigo.auth.api.google;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record GoogleAccessRequest(
        @NotNull UUID flowId,
        @NotBlank @Size(max = 320) String identifier,
        @NotNull @Size(min = 1, max = 128) @Schema(accessMode = Schema.AccessMode.WRITE_ONLY)
                String password) {
    @Override
    public String toString() {
        return "GoogleAccessRequest[redacted]";
    }
}
