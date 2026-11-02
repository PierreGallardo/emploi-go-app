package fr.emploigo.auth.infrastructure.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import fr.emploigo.auth.application.AuthenticationIntegrationTest;
import fr.emploigo.auth.application.confirmation.ConfirmationTokens;
import fr.emploigo.auth.application.mail.AuthenticationMail;
import fr.emploigo.auth.application.mail.AuthenticationMailDispatcher;
import fr.emploigo.auth.application.mail.MailFixtures;
import fr.emploigo.auth.domain.MailFlow;
import fr.emploigo.support.FakeProviders;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * The eight authentication emails, byte for byte: key, sender, subject and text are a contract with
 * the provider.
 */
class AuthenticationMailContentIT extends AuthenticationIntegrationTest {
    private static final String ADDRESS = MailFixtures.ADDRESS;
    private static final String SENDER = MailFixtures.SENDER;
    private static final String ORIGIN = MailFixtures.ORIGIN;
    private static final String SIGNUP_SUBJECT = "Ta demande d’inscription à Emploi Go";
    private static final String NOT_ASKED =
            "Si tu n’as pas demandé cette action, ignore ce message.\n";

    @Autowired AuthenticationMailDispatcher mail;
    @Autowired ConfirmationTokens tokens;

    private UUID pendingVerification() {
        return MailFixtures.pendingVerification(jdbc, clock.instant(), tokens.keyId());
    }

    private UUID pendingReset() {
        return MailFixtures.pendingReset(jdbc, clock.instant(), tokens.keyId());
    }

    private UUID pendingGoogleVerification() {
        return MailFixtures.pendingGoogleVerification(jdbc, clock.instant(), tokens.keyId());
    }

    private AuthenticationMail email(MailFlow flow, String kind, UUID operation) {
        return AuthenticationMail.of(
                flow, kind, operation, ADDRESS, SENDER, ORIGIN, tokens.keyId());
    }

    private void assertMail(
            AuthenticationMail email, String keyPrefix, String subject, String text) {
        UUID id = email.id();
        assertThat(mail.send(email)).as("accepted").isTrue();

        assertThat(FakeProviders.MAIL_PAYLOADS)
                .as("messages sent, by idempotency key")
                .containsOnlyKeys(keyPrefix + "/" + id);
        var message = json.readTree(FakeProviders.MAIL_PAYLOADS.get(keyPrefix + "/" + id));
        assertThat(message.path("from").asString()).as("sender").isEqualTo(SENDER);
        assertThat(json.convertValue(message.path("to"), String[].class))
                .as("recipients")
                .containsExactly(ADDRESS);
        assertThat(message.path("subject").asString()).as("subject").isEqualTo(subject);
        assertThat(message.path("text").asString()).as("text").isEqualTo(text);
        assertThat(List.copyOf(message.propertyNames()))
                .as("fields, in the order of the body")
                .containsExactly("from", "to", "subject", "text");
    }

    @Test
    void theConfirmationEmailCarriesTheCodeOfTheVerificationAndNoLink() {
        UUID verification = pendingVerification();
        var email = email(MailFlow.CONFIRMATION, "CONFIRMATION", verification);

        assertMail(
                email,
                "registration-mail",
                "Ton code Emploi Go : " + tokens.code(verification),
                "Ton code : "
                        + tokens.code(verification)
                        + ".\n\nSaisis-le sur la page d’inscription. Il expire dans 15 minutes.\n\n"
                        + "Ce n’est pas toi ? Ignore cet email.\n");
        assertThat(tokens.code(verification)).as("code").matches("[0-9]{6}");
        assertThat(FakeProviders.MAIL_PAYLOADS.get("registration-mail/" + email.id()))
                .as("payload")
                .doesNotContain("http", ORIGIN, "/confirmation-email");
    }

    static Stream<Arguments> noticesOfAnAccount() {
        return Stream.of(
                argumentSet(
                        "compte avec mot de passe",
                        "ACCOUNT_PASSWORD",
                        "Cette adresse a déjà un compte.\n\nMe connecter : "
                                + ORIGIN
                                + "/connexion\n\nMot de passe oublié ? Choisir un nouveau mot de passe : "
                                + ORIGIN
                                + "/mot-de-passe-oublie\n\nCe n’est pas toi ? Ignore cet email.\n"),
                argumentSet(
                        "compte Google",
                        "ACCOUNT_GOOGLE",
                        "Ton compte utilise Google.\n\nContinuer avec Google : "
                                + ORIGIN
                                + "/connexion-google\n\nCe n’est pas toi ? Ignore cet email.\n"),
                argumentSet(
                        "compte avec mot de passe et Google",
                        "ACCOUNT_BOTH",
                        "Connecte-toi avec ton mot de passe ou Google.\n\nMe connecter : "
                                + ORIGIN
                                + "/connexion\n\nCe n’est pas toi ? Ignore cet email.\n"));
    }

    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("noticesOfAnAccount")
    void theNoticeOfAnAccountCarriesNoCodeAndTheLinksOfItsWayToSignIn(String kind, String text) {
        var email = email(MailFlow.CONFIRMATION, kind, null);

        assertMail(email, "registration-mail", "Tu as déjà un compte Emploi Go", text);
    }

    @Test
    void theGeneralNoticeCarriesNoCodeAndTheLinkOfTheSignup() {
        var email = email(MailFlow.CONFIRMATION, "NOTICE", null);

        assertMail(
                email,
                "registration-mail",
                SIGNUP_SUBJECT,
                "Aucun nouveau code n’a été envoyé à cette adresse.\n\n"
                        + "Pour recevoir un code, recommence ton inscription : "
                        + ORIGIN
                        + "/inscription\n\nCe n’est pas toi ? Ignore cet email.\n");
    }

    @Test
    void theRecoveryEmailCarriesTheLinkOfTheReset() {
        UUID reset = pendingReset();
        var email = email(MailFlow.RECOVERY, "RESET", reset);

        assertMail(
                email,
                "recovery-mail",
                "Ta demande de récupération Emploi Go",
                "Tu as demandé un nouveau mot de passe Emploi Go.\n\nOuvre ce lien puis choisis un nouveau mot de passe :\n"
                        + ORIGIN
                        + "/reinitialiser-mot-de-passe#"
                        + reset
                        + "."
                        + tokens.recoveryToken(reset)
                        + "\n\nCe lien est valable 30 minutes et une seule fois. Un nouveau lien remplace le précédent. Cette demande seule ne change pas ton accès. Si tu ne l’as pas demandée, ignore ce message.\n");
    }

    @Test
    void theRecoveryNoticeCarriesNoLink() {
        var email = email(MailFlow.RECOVERY, "NOTICE", null);

        assertMail(
                email,
                "recovery-mail",
                "Ta demande de récupération Emploi Go",
                "Une demande de récupération Emploi Go a été reçue.\n\nSi tu disposes d’un compte, utilise son adresse vérifiée et sa méthode habituelle de connexion. Une inscription en attente doit être confirmée. Cette demande ne crée pas de mot de passe et ne change aucun accès.\n\n"
                        + NOT_ASKED);
    }

    @Test
    void theGoogleRecoveryEmailPointsToTheRecoveryOfGoogle() {
        var email = email(MailFlow.RECOVERY, "GOOGLE", null);

        assertMail(
                email,
                "recovery-mail",
                "Ta demande de récupération Emploi Go",
                "Ton accès Emploi Go utilise Google. Aucun mot de passe Emploi Go n’a été créé ou changé.\n\nConnecte-toi avec Google ou utilise la récupération de compte Google : https://accounts.google.com/signin/recovery\n\n"
                        + NOT_ASKED);
    }

    @Test
    void thePasswordChangedEmailPointsToTheLogin() {
        var email = email(MailFlow.RECOVERY, "CHANGED", null);

        assertMail(
                email,
                "recovery-mail",
                "Ton mot de passe Emploi Go a changé",
                "Ton mot de passe Emploi Go a été changé. Tes anciens accès ont été révoqués sur tous les appareils.\n\nConnecte-toi normalement avec ton nouveau mot de passe : "
                        + ORIGIN
                        + "/connexion\n\nSi tu n’as pas effectué ce changement, demande immédiatement une nouvelle récupération depuis Emploi Go.\n");
    }

    @Test
    void theGoogleVerificationEmailCarriesTheLinkOfTheVerification() {
        UUID verification = pendingGoogleVerification();
        var email = email(MailFlow.GOOGLE, "VERIFY", verification);

        assertMail(
                email,
                "google-auth",
                "Confirme ton email pour Google sur Emploi Go",
                "Tu as demandé à utiliser Google sur Emploi Go avec cette adresse.\n\nConfirme uniquement une demande que tu as commencée toi-même :\n"
                        + ORIGIN
                        + "/confirmation-google#"
                        + verification
                        + "."
                        + tokens.googleToken(verification)
                        + "\n\nCe lien est valable 24 heures, une seule fois. Un renvoi remplace le précédent. La confirmation ne crée ni compte ni connexion. Après confirmation, reprends explicitement Continuer avec Google dans Emploi Go.\n\n"
                        + NOT_ASKED);
    }

    @Test
    void theGoogleLinkedEmailPointsToTheLogin() {
        var email = email(MailFlow.GOOGLE, "LINKED", null);

        assertMail(
                email,
                "google-auth",
                "Google a été associé à ton compte Emploi Go",
                "Google a été associé à ton compte Emploi Go après vérification de ton mot de passe. Ton pseudo, tes données et tes connexions existantes sont conservés.\n\nTu peux te connecter avec Google ou ton mot de passe habituel : "
                        + ORIGIN
                        + "/connexion\n\nSi tu n’as pas effectué cette association, récupère immédiatement ton accès depuis Emploi Go et contacte le support.\n");
    }
}
