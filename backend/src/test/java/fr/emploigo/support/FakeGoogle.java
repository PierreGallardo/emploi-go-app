package fr.emploigo.support;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Google OpenID Connect provider of {@link FakeProviders}. */
public final class FakeGoogle {
    public static final String CLIENT_ID = "fixture-client";
    public static final String CLIENT_SECRET = "fixture-secret";
    private static final RSAKey KEY = key("fixture-key");
    private static final RSAKey OTHER_KEY = key("other-key");
    private static final ObjectMapper JSON = new ObjectMapper();

    public record Authorization(String nonce, String challenge, String redirect) {
        @Override
        public String toString() {
            return "Authorization[redacted]";
        }
    }

    /** Authorization codes handed out by the tests, consumed once by {@code /token}. */
    public static final Map<String, Authorization> CODES = new ConcurrentHashMap<>();

    public static final AtomicInteger TOKEN_CALLS = new AtomicInteger();

    /**
     * VALID, ERROR (the code exchange fails), ISSUER, AUDIENCE, NONCE, SIGNATURE (another key under
     * the same key id), UNKNOWN_KEY (a key id absent from the key set), EXPIRED, JWKS_ERROR (the
     * key set answers 503) or JWKS_MALFORMED (the key set answers 200 with a body that is not a key
     * set).
     */
    public static volatile String mode = "VALID";

    public static volatile String subject = "fixture-subject";
    public static volatile String email = "fixture@gmail.com";
    public static volatile String hd;
    public static volatile boolean emailVerified = true;

    /**
     * Dates the ID tokens; {@link IntegrationTest} sets the shared {@link TestClock} before every
     * test.
     */
    public static volatile Clock clock = Clock.systemUTC();

    /** The answer of {@code /token}, held back once the authorization code was consumed. */
    public static final Stall TOKEN_RESPONSE = new Stall();

    public static final Stall JWKS_RESPONSE = new Stall();

    private FakeGoogle() {}

    static void reset() {
        mode = "VALID";
        subject = "fixture-subject";
        email = "fixture@gmail.com";
        hd = null;
        emailVerified = true;
        TOKEN_RESPONSE.reset();
        JWKS_RESPONSE.reset();
        CODES.clear();
        TOKEN_CALLS.set(0);
    }

    public static Map<String, String> parameters(String query) {
        var result = new HashMap<String, String>();
        if (query != null) {
            for (String part : query.split("&")) {
                var pieces = part.split("=", 2);
                result.put(
                        URLDecoder.decode(pieces[0], StandardCharsets.UTF_8),
                        pieces.length == 1
                                ? ""
                                : URLDecoder.decode(pieces[1], StandardCharsets.UTF_8));
            }
        }
        return result;
    }

    static void register(HttpServer server) {
        server.createContext(
                "/jwks",
                exchange -> {
                    JWKS_RESPONSE.pass();
                    switch (mode) {
                        case "JWKS_ERROR" ->
                                FakeProviders.respond(
                                        exchange,
                                        503,
                                        "{\"error\":\"unavailable\"}",
                                        "application/json");
                        case "JWKS_MALFORMED" ->
                                FakeProviders.respond(
                                        exchange, 200, "not a key set", "application/json");
                        default ->
                                FakeProviders.respond(
                                        exchange,
                                        200,
                                        new JWKSet(KEY.toPublicJWK()).toString(),
                                        "application/json");
                    }
                });
        server.createContext(
                "/token",
                exchange -> {
                    TOKEN_CALLS.incrementAndGet();
                    var params =
                            parameters(
                                    new String(
                                            exchange.getRequestBody().readAllBytes(),
                                            StandardCharsets.UTF_8));
                    var authorization = CODES.remove(params.get("code"));
                    TOKEN_RESPONSE.pass();
                    int status =
                            authorization != null
                                            && CLIENT_ID.equals(params.get("client_id"))
                                            && CLIENT_SECRET.equals(params.get("client_secret"))
                                            && authorization
                                                    .redirect()
                                                    .equals(params.get("redirect_uri"))
                                            && authorization
                                                    .challenge()
                                                    .equals(
                                                            challenge(
                                                                    params.getOrDefault(
                                                                            "code_verifier", "")))
                                    ? 200
                                    : 400;
                    String body;
                    try {
                        if (mode.equals("ERROR")) status = 503;
                        body =
                                status == 200
                                        ? JSON.writeValueAsString(
                                                Map.of(
                                                        "access_token",
                                                        "fixture-access",
                                                        "token_type",
                                                        "Bearer",
                                                        "expires_in",
                                                        300,
                                                        "scope",
                                                        "openid email",
                                                        "id_token",
                                                        token(authorization)))
                                        : "{\"error\":\"invalid_grant\"}";
                    } catch (Exception error) {
                        throw new IOException("Fixture OIDC indisponible.");
                    }
                    FakeProviders.respond(exchange, status, body, "application/json");
                });
    }

    private static RSAKey key(String id) {
        try {
            return new RSAKeyGenerator(2048).keyID(id).generate();
        } catch (Exception error) {
            throw new ExceptionInInitializerError("Fixture de signature indisponible.");
        }
    }

    private static String challenge(String verifier) {
        try {
            return Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        } catch (Exception error) {
            throw new IllegalStateException("Fixture PKCE invalide.");
        }
    }

    private static String token(Authorization authorization) throws Exception {
        Instant now = clock.instant();
        var claims =
                new JWTClaimsSet.Builder()
                        .issuer(
                                mode.equals("ISSUER")
                                        ? "https://untrusted.invalid"
                                        : FakeProviders.origin())
                        .subject(subject)
                        .audience(mode.equals("AUDIENCE") ? "another-client" : CLIENT_ID)
                        .issueTime(Date.from(now.minusSeconds(10)))
                        .expirationTime(
                                Date.from(
                                        mode.equals("EXPIRED")
                                                ? now.minusSeconds(120)
                                                : now.plusSeconds(300)))
                        .claim("nonce", mode.equals("NONCE") ? "wrong" : authorization.nonce())
                        .claim("email", email)
                        .claim("email_verified", emailVerified);
        if (hd != null) claims.claim("hd", hd);
        var signing = mode.equals("UNKNOWN_KEY") ? OTHER_KEY : KEY;
        var jwt =
                new SignedJWT(
                        new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signing.getKeyID()).build(),
                        claims.build());
        jwt.sign(new RSASSASigner(mode.equals("SIGNATURE") ? OTHER_KEY : signing));
        return jwt.serialize();
    }
}
