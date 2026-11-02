package fr.emploigo.auth.application.quota;

/** What an action does when its counters cannot answer. */
public enum FailureMode {
    /**
     * The action is refused with a 503: it is not executed. For actions that send an email or check
     * a password.
     */
    CLOSED,
    /**
     * The action is executed, with a warning and a metric: its secret or its state is already
     * verified.
     */
    OPEN
}
