package fr.emploigo.auth.application.quota;

import java.time.Duration;

/**
 * The limits of an action counted by its identity alone ({@link QuotaScope#identityOnly()}).
 *
 * @param window the life of the counter, and so the longest wait the quota announces
 */
public record IdentityLimits(int limit, Duration window) {}
