package fr.emploigo.auth.application.password;

/**
 * Refused at once, without waiting or computing, when every allowed simultaneous computation is in
 * progress; it carries no stack trace, since an attack makes it frequent.
 */
public class PasswordHashingBusyException extends RuntimeException {
    public PasswordHashingBusyException() {
        super("Le plafond des calculs d'empreinte simultanés est atteint.", null, false, false);
    }
}
