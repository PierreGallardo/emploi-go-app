package fr.emploigo.auth.domain;

import java.util.Locale;

/** The flows that send an authentication email through the provider. */
public enum MailFlow {
    /**
     * Registration: the confirmation link and the notice for an address that already has a
     * registration or an account.
     */
    CONFIRMATION,
    /** Password recovery: the reset link, the notices, and the notice of a changed password. */
    RECOVERY,
    /**
     * Google: the confirmation of the email of a Google identity, and the notice of a link to an
     * account.
     */
    GOOGLE;

    /** The name of the flow in metrics and logs. */
    public String label() {
        return name().toLowerCase(Locale.ROOT);
    }
}
