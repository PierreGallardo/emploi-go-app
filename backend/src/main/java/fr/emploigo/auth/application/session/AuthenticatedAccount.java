package fr.emploigo.auth.application.session;

import java.util.UUID;

/** {@code publicId} names the account to its own browser; {@code id} never leaves the server. */
public record AuthenticatedAccount(
        long id, UUID publicId, String username, long credentialVersion, UUID familyId) {}
