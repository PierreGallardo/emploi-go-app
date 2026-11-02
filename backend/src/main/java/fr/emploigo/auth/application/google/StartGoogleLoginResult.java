package fr.emploigo.auth.application.google;

import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

/** The request to send to Google, and the proof of the browser that keeps it. */
public record StartGoogleLoginResult(OAuth2AuthorizationRequest request, String proof) {
    @Override
    public String toString() {
        return "StartGoogleLoginResult[redacted]";
    }
}
