package fr.emploigo.auth.api.google;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record GoogleConfirmationRequest(@NotBlank @Size(min = 80, max = 80) String token) {
    @Override
    public String toString() {
        return "GoogleConfirmationRequest[redacted]";
    }
}
