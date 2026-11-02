package fr.emploigo.auth.application.mail;

import fr.emploigo.auth.application.confirmation.ConfirmationTokens;
import fr.emploigo.auth.domain.RegistrationCode;
import fr.emploigo.config.ApiOnly;

import org.springframework.stereotype.Component;

/**
 * What the provider receives for an authentication email. The key and the body depend only on the
 * email, so a repeat of the same send is answered as the same message.
 */
@Component
@ApiOnly
public class AuthenticationMailRenderer {
    public record Message(String idempotencyKey, String subject, String text) {
        @Override
        public String toString() {
            return "Message[redacted]";
        }
    }

    private final ConfirmationTokens tokens;

    public AuthenticationMailRenderer(ConfirmationTokens tokens) {
        this.tokens = tokens;
    }

    public Message render(AuthenticationMail mail) {
        return switch (mail.flow()) {
            case CONFIRMATION ->
                    new Message(
                            key("registration-mail", mail),
                            confirmationSubject(mail),
                            confirmation(mail));
            case RECOVERY ->
                    new Message(
                            key("recovery-mail", mail),
                            mail.kind().equals("CHANGED")
                                    ? "Ton mot de passe Emploi Go a changé"
                                    : "Ta demande de récupération Emploi Go",
                            recovery(mail));
            case GOOGLE ->
                    new Message(
                            key("google-auth", mail),
                            mail.kind().equals("VERIFY")
                                    ? "Confirme ton email pour Google sur Emploi Go"
                                    : "Google a été associé à ton compte Emploi Go",
                            google(mail));
        };
    }

    private static String key(String type, AuthenticationMail mail) {
        return type + "/" + mail.id();
    }

    private String confirmationSubject(AuthenticationMail mail) {
        return switch (mail.kind()) {
            case "CONFIRMATION" -> "Ton code Emploi Go : " + tokens.code(mail.operationId());
            case "ACCOUNT_PASSWORD", "ACCOUNT_GOOGLE", "ACCOUNT_BOTH" ->
                    "Tu as déjà un compte Emploi Go";
            default -> "Ta demande d’inscription à Emploi Go";
        };
    }

    private String confirmation(AuthenticationMail mail) {
        String notYou = "\n\nCe n’est pas toi ? Ignore cet email.\n";
        return switch (mail.kind()) {
            case "CONFIRMATION" ->
                    "Ton code : "
                            + tokens.code(mail.operationId())
                            + ".\n\nSaisis-le sur la page d’inscription. Il expire dans "
                            + RegistrationCode.VALIDITY.toMinutes()
                            + " minutes."
                            + notYou;
            case "ACCOUNT_PASSWORD" ->
                    "Cette adresse a déjà un compte.\n\n"
                            + "Me connecter : "
                            + mail.origin()
                            + "/connexion\n\n"
                            + "Mot de passe oublié ? Choisir un nouveau mot de passe : "
                            + mail.origin()
                            + "/mot-de-passe-oublie"
                            + notYou;
            case "ACCOUNT_GOOGLE" ->
                    "Ton compte utilise Google.\n\n"
                            + "Continuer avec Google : "
                            + mail.origin()
                            + "/connexion-google"
                            + notYou;
            case "ACCOUNT_BOTH" ->
                    "Connecte-toi avec ton mot de passe ou Google.\n\n"
                            + "Me connecter : "
                            + mail.origin()
                            + "/connexion"
                            + notYou;
            default ->
                    "Aucun nouveau code n’a été envoyé à cette adresse.\n\n"
                            + "Pour recevoir un code, recommence ton inscription : "
                            + mail.origin()
                            + "/inscription"
                            + notYou;
        };
    }

    private String recovery(AuthenticationMail mail) {
        return switch (mail.kind()) {
            case "RESET" ->
                    "Tu as demandé un nouveau mot de passe Emploi Go.\n\n"
                            + "Ouvre ce lien puis choisis un nouveau mot de passe :\n"
                            + mail.origin()
                            + "/reinitialiser-mot-de-passe#"
                            + mail.operationId()
                            + "."
                            + tokens.recoveryToken(mail.operationId())
                            + "\n\nCe lien est valable 30 minutes et une seule fois. "
                            + "Un nouveau lien remplace le précédent. "
                            + "Cette demande seule ne change pas ton accès. "
                            + "Si tu ne l’as pas demandée, ignore ce message.\n";
            case "GOOGLE" ->
                    "Ton accès Emploi Go utilise Google. "
                            + "Aucun mot de passe Emploi Go n’a été créé ou changé.\n\n"
                            + "Connecte-toi avec Google ou utilise la récupération de compte Google : https://accounts.google.com/signin/recovery\n\n"
                            + "Si tu n’as pas demandé cette action, ignore ce message.\n";
            case "CHANGED" ->
                    "Ton mot de passe Emploi Go a été changé. "
                            + "Tes anciens accès ont été révoqués sur tous les appareils.\n\n"
                            + "Connecte-toi normalement avec ton nouveau mot de passe : "
                            + mail.origin()
                            + "/connexion\n\n"
                            + "Si tu n’as pas effectué ce changement, demande immédiatement une nouvelle récupération depuis Emploi Go.\n";
            default ->
                    "Une demande de récupération Emploi Go a été reçue.\n\n"
                            + "Si tu disposes d’un compte, utilise son adresse vérifiée et sa méthode habituelle de connexion. "
                            + "Une inscription en attente doit être confirmée. "
                            + "Cette demande ne crée pas de mot de passe et ne change aucun accès.\n\n"
                            + "Si tu n’as pas demandé cette action, ignore ce message.\n";
        };
    }

    private String google(AuthenticationMail mail) {
        if (mail.kind().equals("VERIFY"))
            return "Tu as demandé à utiliser Google sur Emploi Go avec cette adresse.\n\n"
                    + "Confirme uniquement une demande que tu as commencée toi-même :\n"
                    + mail.origin()
                    + "/confirmation-google#"
                    + mail.operationId()
                    + "."
                    + tokens.googleToken(mail.operationId())
                    + "\n\nCe lien est valable 24 heures, une seule fois. "
                    + "Un renvoi remplace le précédent. "
                    + "La confirmation ne crée ni compte ni connexion. "
                    + "Après confirmation, reprends explicitement Continuer avec Google dans Emploi Go.\n\n"
                    + "Si tu n’as pas demandé cette action, ignore ce message.\n";
        return "Google a été associé à ton compte Emploi Go après vérification de ton mot de passe. "
                + "Ton pseudo, tes données et tes connexions existantes sont conservés.\n\n"
                + "Tu peux te connecter avec Google ou ton mot de passe habituel : "
                + mail.origin()
                + "/connexion\n\n"
                + "Si tu n’as pas effectué cette association, récupère immédiatement ton accès depuis Emploi Go et contacte le support.\n";
    }
}
