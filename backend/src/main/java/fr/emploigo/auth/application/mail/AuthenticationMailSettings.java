package fr.emploigo.auth.application.mail;

/**
 * The settings of the authentication emails that the use cases read: whether the channel is
 * enabled, the sender and the public origin of the links.
 */
public record AuthenticationMailSettings(boolean enabled, String sender, String origin) {}
