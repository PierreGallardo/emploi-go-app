package fr.emploigo.auth.application.recovery;

import fr.emploigo.auth.application.mail.AuthenticationMail;
import fr.emploigo.auth.application.mail.AuthenticationMailSettings;
import fr.emploigo.auth.application.mail.MailFixtures;
import fr.emploigo.auth.domain.MailFlow;

/** The values of the recovery tests: a mail, a link, a password and a change. */
final class RecoveryFixtures {
    static final String PASSWORD = "Une-Phrase-2026-fictive!";
    static final AuthenticationMail RESET_MAIL = MailFixtures.mail(MailFlow.RECOVERY, "RESET");
    static final AuthenticationMail CHANGED_MAIL = MailFixtures.mail(MailFlow.RECOVERY, "CHANGED");
    static final String LINK = "0f8fad5b-d9cb-469f-a165-70867728950e." + "s".repeat(43);
    static final RecoveryTransactions.PasswordChanged CHANGED =
            new RecoveryTransactions.PasswordChanged(
                    CHANGED_MAIL, "jeune@example.invalid", "jeune");

    private RecoveryFixtures() {}

    static AuthenticationMailSettings enabledMail() {
        return mail(true);
    }

    static AuthenticationMailSettings disabledMail() {
        return mail(false);
    }

    private static AuthenticationMailSettings mail(boolean enabled) {
        return new AuthenticationMailSettings(
                enabled, "confirmation@example.invalid", "https://example.invalid");
    }
}
