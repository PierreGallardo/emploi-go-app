package fr.emploigo.auth.api.google;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record GoogleLinkRequest(@NotNull UUID flowId, @AssertTrue boolean confirmed) {}
