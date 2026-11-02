package fr.emploigo.auth.api.google;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record GoogleSignupRequest(
        @NotNull UUID flowId,
        @NotNull String username,
        @AssertTrue
                @Schema(
                        description = "Conditions d'utilisation acceptées : seule la valeur true est admise",
                        requiredMode = Schema.RequiredMode.REQUIRED)
                boolean acceptedTerms) {}
