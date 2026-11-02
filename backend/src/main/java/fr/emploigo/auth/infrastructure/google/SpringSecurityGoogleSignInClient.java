package fr.emploigo.auth.infrastructure.google;

import com.nimbusds.jose.KeySourceException;

import fr.emploigo.auth.application.google.GoogleSignInClient;
import fr.emploigo.auth.domain.AuthenticationSecrets;
import fr.emploigo.auth.domain.GoogleIdentity;
import fr.emploigo.config.ApiOnly;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.security.oauth2.client.authentication.OAuth2LoginAuthenticationToken;
import org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.security.oauth2.client.oidc.authentication.OidcAuthorizationCodeAuthenticationProvider;
import org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenDecoderFactory;
import org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenValidator;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationExchange;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationResponse;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Base64;
import java.util.Map;
import java.util.Set;

/** Native Spring OIDC provider, without saving OAuth principals or provider tokens in a session. */
@Component
@ApiOnly
public class SpringSecurityGoogleSignInClient implements GoogleSignInClient {
    private final GoogleConfiguration configuration;
    private final OidcAuthorizationCodeAuthenticationProvider provider;

    public SpringSecurityGoogleSignInClient(GoogleConfiguration configuration, Clock clock) {
        this.configuration = configuration;
        if (!configuration.enabled()) {
            provider = null;
            return;
        }
        var registration = configuration.registration();
        var factory =
                new JdkClientHttpRequestFactory(
                        HttpClient.newBuilder()
                                .connectTimeout(configuration.connectTimeout())
                                .followRedirects(HttpClient.Redirect.NEVER)
                                .build());
        factory.setReadTimeout(configuration.responseTimeout());
        var tokenClient = new RestClientAuthorizationCodeTokenResponseClient();
        tokenClient.setRestClient(
                RestClient.builder()
                        .requestFactory(factory)
                        .requestInterceptor(
                                (request, body, execution) ->
                                        bounded(execution.execute(request, body)))
                        .configureMessageConverters(
                                converters ->
                                        converters
                                                .disableDefaults()
                                                .addCustomConverter(new FormHttpMessageConverter())
                                                .addCustomConverter(
                                                        new OAuth2AccessTokenResponseHttpMessageConverter()))
                        .defaultStatusHandler(new OAuth2ErrorResponseErrorHandler())
                        .build());
        var keys = new RestTemplate(factory);
        keys.setInterceptors(
                java.util.List.of(
                        (request, body, execution) -> bounded(execution.execute(request, body))));
        var decoder =
                NimbusJwtDecoder.withJwkSetUri(registration.getProviderDetails().getJwkSetUri())
                        .jwsAlgorithm(SignatureAlgorithm.RS256)
                        .restOperations(keys)
                        .build();
        decoder.setClaimSetConverter(OidcIdTokenDecoderFactory.createDefaultClaimTypeConverter());
        decoder.setJwtValidator(idTokenValidator(registration, clock));
        var users = new OidcUserService();
        users.setRetrieveUserInfo(request -> false);
        provider = new OidcAuthorizationCodeAuthenticationProvider(tokenClient, users);
        provider.setJwtDecoderFactory(ignored -> decoder);
    }

    /** Checks the ID token dates against the application clock, not the system clock. */
    static OAuth2TokenValidator<Jwt> idTokenValidator(
            ClientRegistration registration, Clock clock) {
        var timestamps = new JwtTimestampValidator();
        timestamps.setClock(clock);
        var oidc = new OidcIdTokenValidator(registration);
        oidc.setClock(clock);
        return new DelegatingOAuth2TokenValidator<>(timestamps, oidc);
    }

    @Override
    public OAuth2AuthorizationRequest authorization() {
        var registration = configuration.registration();
        String nonce = AuthenticationSecrets.generate();
        var builder =
                OAuth2AuthorizationRequest.authorizationCode()
                        .authorizationUri(registration.getProviderDetails().getAuthorizationUri())
                        .clientId(registration.getClientId())
                        .redirectUri(registration.getRedirectUri())
                        .scopes(Set.of("openid", "email"))
                        .state(AuthenticationSecrets.generate())
                        .attributes(Map.of("nonce", nonce))
                        .additionalParameters(
                                Map.of("nonce", nonceHash(nonce), "prompt", "select_account"));
        OAuth2AuthorizationRequestCustomizers.withPkce().accept(builder);
        return builder.build();
    }

    @Override
    public Verification verify(
            OAuth2AuthorizationRequest original, String code, String state, String error) {
        if (error != null)
            return refused(
                    error.equals("access_denied") ? Refusal.CANCELLED : Refusal.PROVIDER_REJECTED);
        if (code == null || code.isBlank() || code.length() > 4096)
            return refused(Refusal.INVALID_FLOW);
        try {
            var response =
                    OAuth2AuthorizationResponse.success(code)
                            .state(state)
                            .redirectUri(configuration.registration().getRedirectUri())
                            .build();
            var result =
                    (OAuth2LoginAuthenticationToken)
                            provider.authenticate(
                                    new OAuth2LoginAuthenticationToken(
                                            configuration.registration(),
                                            new OAuth2AuthorizationExchange(original, response)));
            if (result == null || !(result.getPrincipal() instanceof OidcUser user))
                return refused(Refusal.PROVIDER_REJECTED);
            var token = user.getIdToken();
            return new Verification.Verified(
                    GoogleIdentity.verified(
                            token.getSubject(),
                            token.getEmail(),
                            Boolean.TRUE.equals(token.getEmailVerified()),
                            token.getClaimAsString("hd")));
        } catch (OAuth2AuthenticationException errorResponse) {
            String reason = errorResponse.getError().getErrorCode();
            return refused(
                    keysUnavailable(errorResponse)
                                    || Set.of(
                                                    "server_error",
                                                    "temporarily_unavailable",
                                                    "invalid_token_response")
                                            .contains(reason)
                            ? Refusal.UNAVAILABLE
                            : Refusal.PROVIDER_REJECTED);
        } catch (IllegalArgumentException errorResponse) {
            return refused(Refusal.PROVIDER_REJECTED);
        }
    }

    /**
     * Spring reports a failure to fetch the keys as an {@code invalid_id_token} error, like a bad
     * token; only a {@link KeySourceException} in its causes tells them apart, so it is an outage
     * of Google, not a refusal.
     */
    static boolean keysUnavailable(Throwable error) {
        for (Throwable cause = error.getCause(); cause != null; cause = cause.getCause()) {
            if (cause instanceof KeySourceException) return true;
        }
        return false;
    }

    private static String nonceHash(String value) {
        try {
            return Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.US_ASCII)));
        } catch (java.security.GeneralSecurityException error) {
            throw new IllegalStateException("Nonce OIDC indisponible.", error);
        }
    }

    private static Verification refused(Refusal reason) {
        return new Verification.Refused(reason);
    }

    private static ClientHttpResponse bounded(ClientHttpResponse response) {
        return new ClientHttpResponse() {
            @Override
            public HttpStatusCode getStatusCode() throws IOException {
                return response.getStatusCode();
            }

            @Override
            public String getStatusText() throws IOException {
                return response.getStatusText();
            }

            @Override
            public HttpHeaders getHeaders() {
                return response.getHeaders();
            }

            @Override
            public void close() {
                response.close();
            }

            @Override
            public InputStream getBody() throws IOException {
                return new FilterInputStream(response.getBody()) {
                    private long remaining = 65_536;

                    @Override
                    public int read() throws IOException {
                        int value = super.read();
                        if (value >= 0 && --remaining < 0)
                            throw new IOException("Réponse OIDC trop volumineuse.");
                        return value;
                    }

                    @Override
                    public int read(byte[] bytes, int offset, int length) throws IOException {
                        int count =
                                super.read(
                                        bytes,
                                        offset,
                                        (int) Math.min(length, Math.max(1, remaining + 1)));
                        if (count > 0 && (remaining -= count) < 0)
                            throw new IOException("Réponse OIDC trop volumineuse.");
                        return count;
                    }
                };
            }
        };
    }
}
