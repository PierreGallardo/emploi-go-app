package fr.emploigo.auth.api.session;

import fr.emploigo.auth.application.session.AuthenticatedAccount;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

public record SessionResponse(
        @Schema(
                        description =
                                "Identifiant opaque et stable du compte : il distingue deux"
                                        + " comptes, y compris sans pseudo, sans révéler ni"
                                        + " l'email ni l'ordre des inscriptions",
                        requiredMode = Schema.RequiredMode.REQUIRED)
                UUID accountId,
        @Schema(
                        description =
                                "Pseudo du compte ; null tant qu'il n'est pas choisi, après la"
                                        + " validation du code",
                        requiredMode = Schema.RequiredMode.REQUIRED,
                        nullable = true)
                String username) {
    public static SessionResponse of(AuthenticatedAccount account) {
        return new SessionResponse(account.publicId(), account.username());
    }
}
