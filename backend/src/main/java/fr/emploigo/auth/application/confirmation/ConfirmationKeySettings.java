package fr.emploigo.auth.application.confirmation;

/**
 * The key from which the confirmation codes and links are derived, and whether the channel that
 * sends them is enabled.
 */
public record ConfirmationKeySettings(boolean enabled, byte[] tokenKey) {
    @Override
    public byte[] tokenKey() {
        return tokenKey.clone();
    }
}
