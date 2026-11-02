package fr.emploigo.auth.application.google;

import fr.emploigo.auth.domain.GoogleIdentity;

import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

/** The sign-in with Google: builds the request sent to Google, then checks the identity returned. */
public interface GoogleSignInClient {
    /** Why the provider flow ends without an identity. */
    enum Refusal {
        INVALID_FLOW,
        CANCELLED,
        PROVIDER_REJECTED,
        UNAVAILABLE
    }

    sealed interface Verification {
        record Verified(GoogleIdentity identity) implements Verification {}

        record Refused(Refusal reason) implements Verification {}
    }

    OAuth2AuthorizationRequest authorization();

    Verification verify(
            OAuth2AuthorizationRequest original, String code, String state, String error);
}
