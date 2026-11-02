package fr.emploigo.auth.application.session;

/** {@code reconnectionCookie} is {@code null} when the browser must not keep the login. */
public record AuthenticationGrant(AuthenticatedAccount account, String reconnectionCookie) {
    // Do not let a record's generated toString expose the browser secret.
    @Override
    public String toString() {
        return "AuthenticationGrant[redacted]";
    }
}
