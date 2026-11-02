package fr.emploigo.support;

import static fr.emploigo.support.FakeProviders.MAIL_CALLS;
import static fr.emploigo.support.FakeProviders.MAIL_SENDS;

/**
 * Provider calls and distinct messages since the counters were taken: relative to the scenario, not
 * a global total.
 */
public final class MailCounters {
    private final int callsBefore = MAIL_CALLS.get();
    private final int sendsBefore = MAIL_SENDS.get();

    public int calls() {
        return MAIL_CALLS.get() - callsBefore;
    }

    public int sends() {
        return MAIL_SENDS.get() - sendsBefore;
    }
}
