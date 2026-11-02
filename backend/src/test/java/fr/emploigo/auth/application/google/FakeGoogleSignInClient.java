package fr.emploigo.auth.application.google;

import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

/** A provider whose answer the test chooses, and which counts the verifications it is asked for. */
public final class FakeGoogleSignInClient implements GoogleSignInClient {
    private Verification answer = new Verification.Refused(Refusal.INVALID_FLOW);
    private int verifications;

    public void answer(Verification answer) {
        this.answer = answer;
    }

    public int verifications() {
        return verifications;
    }

    @Override
    public OAuth2AuthorizationRequest authorization() {
        return OAuth2AuthorizationRequest.authorizationCode()
                .authorizationUri("https://accounts.example.invalid/authorize")
                .clientId("client")
                .state("state")
                .build();
    }

    @Override
    public Verification verify(
            OAuth2AuthorizationRequest original, String code, String state, String error) {
        verifications++;
        return answer;
    }
}
