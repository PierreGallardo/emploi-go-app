package fr.emploigo.auth.infrastructure.google;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import com.nimbusds.jose.RemoteKeySourceException;
import com.nimbusds.jose.jwk.source.JWKSetUnavailableException;
import com.nimbusds.jose.proc.BadJOSEException;
import com.nimbusds.jose.proc.BadJWSException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Stream;

class SpringSecurityGoogleSignInClientTest {
    private static final String CLIENT_ID = "fixture-client";
    private static final ClientRegistration GOOGLE =
            CommonOAuth2Provider.GOOGLE
                    .getBuilder("google")
                    .clientId(CLIENT_ID)
                    .clientSecret("fixture-secret")
                    .build();

    /**
     * Far beyond the 60 s tolerance of the validators, so the system clock can never be the one in
     * use.
     */
    private static final Duration DRIFT = Duration.ofHours(2);

    private static Jwt idToken(Instant issuedAt) {
        return Jwt.withTokenValue("fixture-token")
                .header("alg", "RS256")
                .issuer(GOOGLE.getProviderDetails().getIssuerUri())
                .subject("fixture-subject")
                .audience(List.of(CLIENT_ID))
                .issuedAt(issuedAt)
                .expiresAt(issuedAt.plus(Duration.ofMinutes(5)))
                .build();
    }

    static Stream<Arguments> causesOfInvalidIdToken() {
        return Stream.of(
                argumentSet(
                        "clés inaccessibles",
                        true,
                        new RemoteKeySourceException("I/O error", new IOException())),
                argumentSet(
                        "attente d'un rafraîchissement des clés dépassée",
                        true,
                        new JWKSetUnavailableException("Timeout while waiting for refresh")),
                argumentSet(
                        "signature invalide",
                        false,
                        new BadJWSException("Signed JWT rejected: Invalid signature")),
                argumentSet(
                        "aucune clé pour l'identifiant du jeton",
                        false,
                        new BadJOSEException("no matching key(s) found")));
    }

    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("causesOfInvalidIdToken")
    void keysAvailabilityIsReadFromTheCauseOfAnInvalidIdTokenError(
            boolean unavailable, Exception cause) {
        var error =
                new OAuth2AuthenticationException(
                        new OAuth2Error("invalid_id_token"),
                        "decode",
                        new JwtException("decode", cause));

        assertThat(SpringSecurityGoogleSignInClient.keysUnavailable(error))
                .as("outage of the keys of Google")
                .isEqualTo(unavailable);
    }

    @Test
    void idTokenDatedByTheApplicationClockIsAcceptedFarFromSystemTime() {
        var application = Clock.fixed(Instant.now().plus(DRIFT), ZoneOffset.UTC);

        var result =
                SpringSecurityGoogleSignInClient.idTokenValidator(GOOGLE, application)
                        .validate(idToken(application.instant()));

        assertThat(result.getErrors()).as("validation errors").isEmpty();
    }

    @Test
    void idTokenExpiredForTheApplicationClockIsRefusedThoughValidForTheSystemClock() {
        var application = Clock.fixed(Instant.now().plus(DRIFT), ZoneOffset.UTC);

        var result =
                SpringSecurityGoogleSignInClient.idTokenValidator(GOOGLE, application)
                        .validate(idToken(Instant.now()));

        assertThat(result.getErrors())
                .as("validation errors")
                .extracting(OAuth2Error::getDescription)
                .anySatisfy(description -> assertThat(description).contains("exp"));
    }
}
