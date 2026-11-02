package fr.emploigo.auth.api.registration;

import jakarta.validation.constraints.NotNull;

/** The rule of the username is checked by the use case, so that a refusal has its own code. */
public record UsernameRequest(@NotNull String username) {}
