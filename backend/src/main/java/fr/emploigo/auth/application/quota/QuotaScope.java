package fr.emploigo.auth.application.quota;

/** An action whose attempts the quota counts, each under its {@code key}. */
public enum QuotaScope {
    LOGIN("login", false, true, true),
    REGISTRATION_MAIL("registration-mail", true, true),
    CONFIRMATION("confirmation", false, false),
    RECOVERY_MAIL("recovery-mail", true, true),
    RECOVERY_CONFIRM("recovery-confirm", false, true),
    GOOGLE_START("google-start", false, false),
    GOOGLE_CALLBACK("google-callback", false, false),
    GOOGLE_COMPLETE("google-complete", false, false),
    GOOGLE_LINK("google-link", false, true),
    GOOGLE_MAIL("google-mail", true, true),
    GOOGLE_EMAIL_CONFIRM("google-email-confirm", false, false),
    RESUME("resume", false, false, false, true);

    private final String key;
    private final boolean mail;
    private final boolean sensitive;
    private final boolean failureDelay;
    private final boolean identityOnly;

    QuotaScope(String key, boolean mail, boolean sensitive) {
        this(key, mail, sensitive, false, false);
    }

    QuotaScope(String key, boolean mail, boolean sensitive, boolean failureDelay) {
        this(key, mail, sensitive, failureDelay, false);
    }

    QuotaScope(
            String key,
            boolean mail,
            boolean sensitive,
            boolean failureDelay,
            boolean identityOnly) {
        // The limit by prefix of an email action is checked against its address limit, which an
        // identity-only action lacks.
        if (mail && identityOnly)
            throw new IllegalArgumentException(
                    "Une action d'email a une limite par adresse : " + key);
        this.key = key;
        this.mail = mail;
        this.sensitive = sensitive;
        this.failureDelay = failureDelay;
        this.identityOnly = identityOnly;
    }

    /** The name of the action in the counters and in the configuration. */
    public String key() {
        return key;
    }

    public boolean mail() {
        return mail;
    }

    /**
     * Whether the action counts the failures of an identity and answers them with a growing delay,
     * instead of limiting the attempts of an identity.
     */
    public boolean failureDelay() {
        return failureDelay;
    }

    /**
     * Whether the action is counted by identity alone, with no address limit: a shared address (the
     * room of the Mission Locale) would refuse young people, and the identity is counted only once
     * its secret is verified, so no client can create counters by varying it.
     */
    public boolean identityOnly() {
        return identityOnly;
    }

    /**
     * Whether the action checks a password or sends an email. Such an action is refused when the
     * counters cannot answer.
     */
    public boolean sensitive() {
        return sensitive;
    }

    /**
     * What the action does when its counters cannot answer: a sensitive action is refused, and any
     * other is executed with a warning.
     */
    public FailureMode failureMode() {
        return sensitive ? FailureMode.CLOSED : FailureMode.OPEN;
    }
}
