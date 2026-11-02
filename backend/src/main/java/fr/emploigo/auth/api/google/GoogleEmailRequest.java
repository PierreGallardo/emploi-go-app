package fr.emploigo.auth.api.google;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record GoogleEmailRequest(@NotNull UUID flowId) {}
