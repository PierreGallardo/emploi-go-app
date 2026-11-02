package fr.emploigo.auth.api.google;

import fr.emploigo.auth.application.google.GoogleTransactions;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

public record GoogleStatusResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean pending,
        @Schema(nullable = true) UUID flowId,
        @Schema(nullable = true) GoogleTransactions.Stage stage,
        @Schema(nullable = true) String errorCode) {}
