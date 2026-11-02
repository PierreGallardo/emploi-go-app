package fr.emploigo.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Exposure rules of the recorded contract: {@code npm run check -- contract} only proves the file
 * equals what the API generates, so these rules catch a route that must stay out of it.
 */
class ApiContractRulesTest {
    private JsonNode document;
    private Collection<String> paths;

    @BeforeEach
    void readRecordedContract() throws IOException {
        document = recordedContract();
        paths = document.path("paths").propertyNames();
        assertThat(paths).as("given: paths of the recorded contract").isNotEmpty();
    }

    private static JsonNode recordedContract() throws IOException {
        var file = Path.of(System.getProperty("basedir", "."), "..", "contracts", "openapi.json");
        return new ObjectMapper().readTree(Files.readString(file));
    }

    static Stream<String> rateLimitedOperations() throws IOException {
        return operationsAnswering("429", "/");
    }

    static Stream<String> unavailableAuthenticationOperations() throws IOException {
        return operationsAnswering("503", "/api/auth/");
    }

    /** The operations, as {@code "GET /path"}, that declare {@code status} under {@code prefix}. */
    private static Stream<String> operationsAnswering(String status, String prefix)
            throws IOException {
        var paths = recordedContract().path("paths");
        return paths.propertyNames().stream()
                .filter(path -> path.startsWith(prefix))
                .flatMap(
                        path ->
                                paths.path(path).propertyNames().stream()
                                        .filter(
                                                method ->
                                                        paths.path(path)
                                                                .path(method)
                                                                .path("responses")
                                                                .has(status))
                                        .map(
                                                method ->
                                                        method.toUpperCase(Locale.ROOT)
                                                                + " "
                                                                + path));
    }

    private JsonNode retryAfterOf(String operation, String status) {
        var parts = operation.split(" ", 2);
        return document.path("paths")
                .path(parts[1])
                .path(parts[0].toLowerCase(Locale.ROOT))
                .path("responses")
                .path(status)
                .path("headers")
                .path("Retry-After");
    }

    @Test
    void googleCallbackIsHandledBySecurityAndIsNotInTheContract() {
        assertThat(paths).as("paths").doesNotContain("/api/auth/google/callback/google");
    }

    @Test
    void everyPathIsUnderTheApiPrefix() {
        assertThat(paths).as("paths").allSatisfy(path -> assertThat(path).startsWith("/api/"));
    }

    /**
     * The screens read Retry-After to say when to retry: the description of a 429 is text, so the
     * header must be declared.
     */
    @ParameterizedTest
    @MethodSource("rateLimitedOperations")
    void aRateLimitResponseDeclaresARequiredRetryAfter(String operation) {
        var header = retryAfterOf(operation, "429");

        assertThat(header.isMissingNode())
                .as("Retry-After declared on 429 of " + operation)
                .isFalse();
        assertThat(header.path("required").asBoolean(false))
                .as("Retry-After required on " + operation)
                .isTrue();
    }

    static Stream<Arguments> routes() {
        return Stream.of(
                argumentSet("ping", "get", "/api/ping", Set.of("200", "503")),
                argumentSet("csrf", "get", "/api/auth/csrf", Set.of("204", "503")),
                argumentSet(
                        "connexion",
                        "post",
                        "/api/auth/login",
                        Set.of("200", "400", "401", "403", "429", "503")),
                argumentSet(
                        "choix du pseudo",
                        "post",
                        "/api/auth/username",
                        Set.of("200", "400", "401", "403", "409", "422", "503")),
                argumentSet(
                        "reprise",
                        "post",
                        "/api/auth/resume",
                        Set.of("200", "401", "403", "429", "503")),
                argumentSet("session", "get", "/api/auth/session", Set.of("200", "401", "503")),
                argumentSet("déconnexion", "post", "/api/auth/logout", Set.of("204", "403", "503")),
                argumentSet(
                        "inscription",
                        "post",
                        "/api/auth/signup",
                        Set.of("202", "400", "403", "429", "503")),
                argumentSet(
                        "nouveau code d'inscription",
                        "post",
                        "/api/auth/signup/resend",
                        Set.of("202", "400", "403", "410", "429", "503")),
                argumentSet(
                        "confirmation d'inscription",
                        "post",
                        "/api/auth/signup/confirm",
                        Set.of("200", "400", "403", "409", "410", "429", "503")),
                argumentSet(
                        "demande de récupération",
                        "post",
                        "/api/auth/password/forgot",
                        Set.of("202", "400", "403", "429", "503")),
                argumentSet(
                        "changement de mot de passe",
                        "post",
                        "/api/auth/password/reset",
                        Set.of("200", "400", "403", "410", "429", "503")),
                argumentSet(
                        "départ Google",
                        "post",
                        "/api/auth/google/start",
                        Set.of("200", "403", "429", "503")),
                argumentSet(
                        "étape Google",
                        "get",
                        "/api/auth/google/status",
                        Set.of("200", "400", "410", "503")),
                argumentSet(
                        "accès Google",
                        "post",
                        "/api/auth/google/access",
                        Set.of("200", "400", "401", "403", "409", "410", "429", "503")),
                argumentSet(
                        "inscription Google",
                        "post",
                        "/api/auth/google/signup",
                        Set.of("200", "400", "403", "409", "410", "422", "429", "503")),
                argumentSet(
                        "association Google",
                        "post",
                        "/api/auth/google/link",
                        Set.of("200", "400", "403", "409", "410", "429", "503")),
                argumentSet(
                        "lien Google par email",
                        "post",
                        "/api/auth/google/email",
                        Set.of("202", "400", "403", "410", "429", "503")),
                argumentSet(
                        "confirmation de l'email Google",
                        "post",
                        "/api/auth/google/email/confirm",
                        Set.of("204", "400", "403", "410", "429", "503")),
                argumentSet(
                        "recherche de la carte",
                        "get",
                        "/api/establishments",
                        Set.of("200", "400", "401", "403", "503")),
                argumentSet(
                        "points de la carte",
                        "get",
                        "/api/establishments/points",
                        Set.of("200", "400", "401", "403", "503")),
                argumentSet(
                        "suggestions « Quoi ? »",
                        "get",
                        "/api/establishments/suggestions",
                        Set.of("200", "400", "401", "403", "503")),
                argumentSet(
                        "fiche d'un établissement",
                        "get",
                        "/api/establishments/{siret}",
                        Set.of("200", "400", "401", "403", "404", "503")),
                argumentSet(
                        "suggestions « Où ? »",
                        "get",
                        "/api/places/suggestions",
                        Set.of("200", "400", "401", "403", "503")),
                argumentSet(
                        "sources de la carte",
                        "get",
                        "/api/datasets",
                        Set.of("200", "401", "403", "503")));
    }

    /**
     * A response declared on the controller class replaces the one of a route, so a route declares
     * only the statuses it can answer.
     */
    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("routes")
    void aRouteDeclaresOnlyTheStatusesItCanAnswer(
            String method, String path, Set<String> statuses) {
        var responses = document.path("paths").path(path).path(method).path("responses");

        assertThat(responses.propertyNames())
                .as("statuses of " + path)
                .containsExactlyInAnyOrderElementsOf(statuses);
    }

    @Test
    void everyOperationOfTheContractIsListedWithItsStatuses() {
        var listed =
                routes()
                        .map(
                                arguments ->
                                        ((String) arguments.get()[0]).toUpperCase(Locale.ROOT)
                                                + " "
                                                + arguments.get()[1])
                        .collect(Collectors.toSet());
        var declared =
                paths.stream()
                        .flatMap(
                                path ->
                                        document.path("paths").path(path).propertyNames().stream()
                                                .map(
                                                        method ->
                                                                method.toUpperCase(Locale.ROOT)
                                                                        + " "
                                                                        + path))
                        .collect(Collectors.toSet());

        assertThat(listed).as("listed operations").containsExactlyInAnyOrderElementsOf(declared);
    }

    /**
     * A database outage has no known duration, so a 503 may come without Retry-After: the header is
     * declared, and optional.
     */
    @ParameterizedTest
    @MethodSource("unavailableAuthenticationOperations")
    void anUnavailableResponseDeclaresAnOptionalRetryAfter(String operation) {
        var header = retryAfterOf(operation, "503");

        assertThat(header.isMissingNode())
                .as("Retry-After declared on 503 of " + operation)
                .isFalse();
        assertThat(header.path("required").asBoolean(true))
                .as("Retry-After optional on " + operation)
                .isFalse();
    }

    /**
     * A route without a quota cannot answer 429: declaring it would promise a wait that never
     * comes.
     */
    @Test
    void googleStatusHasNoQuotaSoItDeclaresNoRateLimit() {
        var responses =
                document.path("paths")
                        .path("/api/auth/google/status")
                        .path("get")
                        .path("responses");

        assertThat(responses.isMissingNode()).as("given: the route of the Google status").isFalse();
        assertThat(responses.has("429")).as("429 on GET /api/auth/google/status").isFalse();
    }
}
