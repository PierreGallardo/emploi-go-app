package fr.emploigo.auth.application.failure;

import java.time.Duration;
import java.util.Objects;

public class AuthenticationFailedException extends RuntimeException {
    public enum Reason {
        INVALID_CREDENTIALS,
        RECONNECTION_REFUSED,
        UNAUTHENTICATED,
        RATE_LIMITED,
        MAIL_RECENTLY_SENT,
        CODES_BLOCKED,
        UNAVAILABLE
    }

    private final Reason reason;
    private final Duration retryAfter;

    private AuthenticationFailedException(Reason reason, Duration retryAfter) {
        super(reason.name());
        this.reason = reason;
        this.retryAfter = retryAfter;
    }

    /** The identifier or the secret is wrong, or the account may not log in. */
    public static AuthenticationFailedException invalidCredentials() {
        return new AuthenticationFailedException(Reason.INVALID_CREDENTIALS, null);
    }

    /** The reconnection cookie is unreadable, unknown, expired, revoked or replayed. */
    public static AuthenticationFailedException reconnectionRefused() {
        return new AuthenticationFailedException(Reason.RECONNECTION_REFUSED, null);
    }

    /** The session names an account or a reconnection that no longer holds. */
    public static AuthenticationFailedException unauthenticated() {
        return new AuthenticationFailedException(Reason.UNAUTHENTICATED, null);
    }

    public static AuthenticationFailedException rateLimited(Duration retryAfter) {
        return new AuthenticationFailedException(
                Reason.RATE_LIMITED, Objects.requireNonNull(retryAfter));
    }

    /**
     * An email was sent to this address less than {@code retryAfter} ago: the request is answered
     * by the email already on its way, and no other is sent.
     */
    public static AuthenticationFailedException mailRecentlySent(Duration retryAfter) {
        return new AuthenticationFailedException(
                Reason.MAIL_RECENTLY_SENT, Objects.requireNonNull(retryAfter));
    }

    /**
     * The registration has no new code and no attempt for {@code retryAfter}: too many codes were
     * refused. Apart from {@link #rateLimited}, which is a quota, so that a page can tell the two.
     */
    public static AuthenticationFailedException codesBlocked(Duration retryAfter) {
        return new AuthenticationFailedException(Reason.CODES_BLOCKED, Objects.requireNonNull(retryAfter));
    }

    /**
     * The counters of the quota cannot answer, and the action is not allowed to run without them.
     */
    public static AuthenticationFailedException unavailable() {
        return new AuthenticationFailedException(Reason.UNAVAILABLE, null);
    }

    public Reason reason() {
        return reason;
    }

    /**
     * The wait announced by a {@link Reason#RATE_LIMITED} or {@link Reason#MAIL_RECENTLY_SENT}
     * failure, {@code null} for any other.
     */
    public Duration retryAfter() {
        return retryAfter;
    }
}
