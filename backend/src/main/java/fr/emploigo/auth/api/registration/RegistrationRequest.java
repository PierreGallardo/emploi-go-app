package fr.emploigo.auth.api.registration;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegistrationRequest(
        @NotBlank @Email @Size(max = 320) String email,
        @NotBlank @Size(min = 8, max = 128) String password,
        @NotBlank @Size(min = 8, max = 128) String confirmation,
        @AssertTrue
                @Schema(
                        description = "Conditions d'utilisation acceptées : seule la valeur true est admise",
                        requiredMode = Schema.RequiredMode.REQUIRED)
                boolean acceptedTerms,
        @Schema(
                        description =
                                "Rester connecté sur ce navigateur pendant 30 jours après la"
                                        + " confirmation ; sinon la session s'arrête à sa"
                                        + " fermeture ou après l'inactivité",
                        requiredMode = Schema.RequiredMode.REQUIRED)
                boolean rememberMe) {
    @Override
    public String toString() {
        return "RegistrationRequest[redacted]";
    }
}
