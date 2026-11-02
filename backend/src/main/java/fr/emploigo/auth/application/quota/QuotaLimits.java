package fr.emploigo.auth.application.quota;

import java.time.Duration;

/**
 * The limits of one action, carried by each call so that the counters' adapter reads no
 * configuration. An identity is counted only while its address is within the address limit. {@code
 * mail} is {@code null} for an action that sends no email.
 */
public record QuotaLimits(int addressLimit, int identityLimit, Duration window, MailLimits mail) {
    /**
     * Sends above the global threshold raise an alert but are not refused. The prefix limit counts
     * only the attempts that the address, the identity and the delay let through; an IPv4 client
     * has no prefix.
     */
    public record MailLimits(Duration sendDelay, int globalAlertThreshold, int prefixLimit) {}
}
