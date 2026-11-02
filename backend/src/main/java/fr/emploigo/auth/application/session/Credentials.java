package fr.emploigo.auth.application.session;

/** The account proven by a password or a Google login, with its credential version at that time. */
public record Credentials(long accountId, long version) {}
