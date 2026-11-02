package fr.emploigo.auth.infrastructure.google;

import static fr.emploigo.support.FakeGoogle.CODES;
import static fr.emploigo.support.FakeGoogle.JWKS_RESPONSE;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import fr.emploigo.auth.application.google.GoogleSignInClient.Refusal;
import fr.emploigo.auth.application.google.GoogleSignInClient.Verification;
import fr.emploigo.auth.infrastructure.mail.AuthenticationMailConfiguration;
import fr.emploigo.support.FakeGoogle.Authorization;
import fr.emploigo.support.FakeGoogle;
import fr.emploigo.support.FakeProviders;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

import java.net.URI;
import java.time.Clock;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * A client built for each test starts with an empty key cache, so the result does not depend on the
 * order of the tests.
 */
class SpringSecurityGoogleSignInClientKeySetTimeoutTest {
    private SpringSecurityGoogleSignInClient client;

    @AfterEach
    void releaseProviders() {
        FakeProviders.reset();
    }

    @BeforeEach
    void fixture() {
        FakeProviders.reset();
        FakeGoogle.clock = Clock.systemUTC();
        var mail = mock(AuthenticationMailConfiguration.class);
        when(mail.origin()).thenReturn("http://localhost:5173");
        when(mail.enabled()).thenReturn(true);
        var environment =
                new MockEnvironment()
                        .withProperty("emploi-go.google.enabled", "true")
                        .withProperty("emploi-go.google.client-id", FakeGoogle.CLIENT_ID)
                        .withProperty("emploi-go.google.client-secret", FakeGoogle.CLIENT_SECRET)
                        .withProperty(
                                "emploi-go.google.redirect-uri",
                                "http://localhost:5173/api/auth/google/callback/google")
                        .withProperty(
                                "emploi-go.google.authorization-uri",
                                FakeProviders.origin() + "/authorize")
                        .withProperty(
                                "emploi-go.google.token-uri", FakeProviders.origin() + "/token")
                        .withProperty(
                                "emploi-go.google.jwk-set-uri", FakeProviders.origin() + "/jwks")
                        .withProperty("emploi-go.google.issuer-uri", FakeProviders.origin())
                        .withProperty("emploi-go.google.response-timeout", "500ms");
        environment.setActiveProfiles("test");
        client =
                new SpringSecurityGoogleSignInClient(new GoogleConfiguration(environment, mail), Clock.systemUTC());
    }

    private record Attempt(OAuth2AuthorizationRequest request, String code) {}

    private static Attempt attempt(SpringSecurityGoogleSignInClient client) {
        var request = client.authorization();
        var parameters =
                FakeGoogle.parameters(
                        URI.create(request.getAuthorizationRequestUri()).getRawQuery());
        String code = UUID.randomUUID().toString();
        CODES.put(
                code,
                new Authorization(
                        parameters.get("nonce"),
                        parameters.get("code_challenge"),
                        parameters.get("redirect_uri")));
        return new Attempt(request, code);
    }

    @Test
    void keysAnsweringAfterTheDelayAreReportedUnavailableWithoutAcceptingTheToken() {
        var attempt = attempt(client);

        try (var hold = JWKS_RESPONSE.hold()) {
            assertThat(verify(attempt))
                    .as("verification of the ID token")
                    .isEqualTo(new Verification.Refused(Refusal.UNAVAILABLE));
            assertThat(hold.expired())
                    .as("hold ended by the fixture limit, not by the client delay")
                    .isFalse();
            assertThat(hold.requests()).as("requests held by the provider").isEqualTo(1);
        }
    }

    static Stream<Arguments> unusableKeySets() {
        return Stream.of(
                argumentSet("point des clés en erreur HTTP 503", "JWKS_ERROR"),
                argumentSet("réponse qui n'est pas un ensemble de clés", "JWKS_MALFORMED"));
    }

    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("unusableKeySets")
    void keysUnusableAreReportedUnavailable(String mode) {
        FakeGoogle.mode = mode;
        var attempt = attempt(client);

        assertThat(verify(attempt))
                .as("verification of the ID token")
                .isEqualTo(new Verification.Refused(Refusal.UNAVAILABLE));
    }

    @Test
    void verificationSucceedsOnTheSameClientOnceTheKeysAnswerInTime() {
        var slow = attempt(client);
        try (var _ = JWKS_RESPONSE.hold()) {
            assertThat(verify(slow))
                    .as("given: slow keys")
                    .isInstanceOf(Verification.Refused.class);
        }
        var retry = attempt(client);

        assertThat(verify(retry))
                .as("verification")
                .isInstanceOfSatisfying(
                        Verification.Verified.class,
                        verified ->
                                assertThat(verified.identity().subject())
                                        .as("subject")
                                        .isEqualTo("fixture-subject"));
    }

    private Verification verify(Attempt attempt) {
        return client.verify(
                attempt.request(), attempt.code(), attempt.request().getState(), null);
    }

    private record Call(
            OAuth2AuthorizationRequest original, String code, String state, String error) {}

    static Stream<Arguments> refusalsBeforeAnyCallToTheProvider() {
        return Stream.of(
                argumentSet(
                        "refus de l'utilisateur chez Google",
                        (Function<Attempt, Call>)
                                a ->
                                        new Call(
                                                a.request(),
                                                null,
                                                a.request().getState(),
                                                "access_denied"),
                        Refusal.CANCELLED),
                argumentSet(
                        "autre erreur renvoyée par Google",
                        (Function<Attempt, Call>)
                                a ->
                                        new Call(
                                                a.request(),
                                                null,
                                                a.request().getState(),
                                                "server_error"),
                        Refusal.PROVIDER_REJECTED),
                argumentSet(
                        "sans code",
                        (Function<Attempt, Call>)
                                a -> new Call(a.request(), null, a.request().getState(), null),
                        Refusal.INVALID_FLOW),
                argumentSet(
                        "code vide",
                        (Function<Attempt, Call>)
                                a -> new Call(a.request(), " ", a.request().getState(), null),
                        Refusal.INVALID_FLOW),
                argumentSet(
                        "code trop long",
                        (Function<Attempt, Call>)
                                a ->
                                        new Call(
                                                a.request(),
                                                "c".repeat(4097),
                                                a.request().getState(),
                                                null),
                        Refusal.INVALID_FLOW));
    }

    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("refusalsBeforeAnyCallToTheProvider")
    void inputRefusedBeforeTheProviderIsCalledKeepsItsReason(
            Function<Attempt, Call> input, Refusal expected) {
        var call = input.apply(attempt(client));

        assertThat(client.verify(call.original(), call.code(), call.state(), call.error()))
                .as("verification")
                .isEqualTo(new Verification.Refused(expected));
        assertThat(FakeGoogle.TOKEN_CALLS).as("token endpoint calls").hasValue(0);
    }

    static Stream<Arguments> refusalsOfTheProvider() {
        return Stream.of(
                argumentSet(
                        "nonce différent de celui de la requête",
                        "NONCE",
                        Refusal.PROVIDER_REJECTED),
                argumentSet("émetteur qui n'est pas Google", "ISSUER", Refusal.PROVIDER_REJECTED),
                argumentSet("audience d'un autre client", "AUDIENCE", Refusal.PROVIDER_REJECTED),
                argumentSet("signature d'une autre clé", "SIGNATURE", Refusal.PROVIDER_REJECTED),
                argumentSet(
                        "clé absente des clés de Google",
                        "UNKNOWN_KEY",
                        Refusal.PROVIDER_REJECTED),
                argumentSet("jeton déjà expiré", "EXPIRED", Refusal.PROVIDER_REJECTED),
                argumentSet("échange du code en erreur HTTP 503", "ERROR", Refusal.UNAVAILABLE));
    }

    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("refusalsOfTheProvider")
    void refusalOfTheProviderKeepsItsReason(String mode, Refusal expected) {
        FakeGoogle.mode = mode;

        assertThat(verify(attempt(client)))
                .as("verification")
                .isEqualTo(new Verification.Refused(expected));
    }

    @Test
    void interruptedThreadIsReportedUnavailableAndStaysInterrupted() {
        var attempt = attempt(client);
        Thread.currentThread().interrupt();
        try {
            assertThat(verify(attempt))
                    .as("verification")
                    .isEqualTo(new Verification.Refused(Refusal.UNAVAILABLE));
            assertThat(Thread.currentThread().isInterrupted()).as("interrupted").isTrue();
        } finally {
            Thread.interrupted();
        }
    }
}
