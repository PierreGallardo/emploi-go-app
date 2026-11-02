package fr.emploigo.auth.api;

import fr.emploigo.config.ApiOnly;

import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.JsonSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;

import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

import java.math.BigDecimal;

@Configuration
@ApiOnly
class AuthenticationOpenApiConfiguration {
    private static final String AUTHENTICATION_PATHS = "/api/auth/";

    /**
     * Describes the logout of Spring Security, which no controller declares. Ordered first so that
     * {@link #retryAfterOfAuthenticationLimits} completes its 503 too.
     */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    OpenApiCustomizer logoutRoute() {
        return document -> {
            var csrf =
                    new Parameter()
                            .in("header")
                            .name("X-CSRF-TOKEN")
                            .required(true)
                            .schema(new JsonSchema().typesItem("string"));
            var responses =
                    new ApiResponses()
                            .addApiResponse("204", new ApiResponse().description("Accès révoqués"))
                            .addApiResponse("403", error("Jeton CSRF invalide"))
                            .addApiResponse(
                                    "503",
                                    error("Base de données indisponible pour révoquer les accès"));
            var logout =
                    new Operation()
                            .operationId("logout")
                            .summary("Révoquer les accès de ce navigateur")
                            .addTagsItem("authentication-controller")
                            .addParametersItem(csrf)
                            .responses(responses);
            if (document.getPaths() == null) document.setPaths(new Paths());
            document.getPaths()
                    .addPathItem(AUTHENTICATION_PATHS + "logout", new PathItem().post(logout));
        };
    }

    private static ApiResponse error(String description) {
        var schema = new JsonSchema().$ref(AuthenticationErrorResponse.class.getSimpleName());
        return new ApiResponse()
                .description(description)
                .content(new Content().addMediaType("*/*", new MediaType().schema(schema)));
    }

    /**
     * Adds the Retry-After header to every 429 of the authentication routes (required) and every
     * 503 (optional: a database outage has no known duration), so that a new route does not copy
     * it.
     */
    @Bean
    OpenApiCustomizer retryAfterOfAuthenticationLimits() {
        return document -> {
            if (document.getPaths() == null) return;
            document.getPaths().entrySet().stream()
                    .filter(path -> path.getKey().startsWith(AUTHENTICATION_PATHS))
                    .flatMap(path -> path.getValue().readOperations().stream())
                    .filter(operation -> operation.getResponses() != null)
                    .forEach(
                            operation -> {
                                var limited = operation.getResponses().get("429");
                                if (limited != null)
                                    limited.addHeaderObject("Retry-After", retryAfter(true));
                                var unavailable = operation.getResponses().get("503");
                                if (unavailable != null)
                                    unavailable.addHeaderObject("Retry-After", retryAfter(false));
                            });
        };
    }

    private static Header retryAfter(boolean required) {
        return new Header()
                .description("Attente avant une nouvelle tentative, en secondes")
                .required(required)
                .style(Header.StyleEnum.SIMPLE)
                .schema(
                        new JsonSchema()
                                .typesItem("integer")
                                .format("int64")
                                .minimum(BigDecimal.ONE));
    }
}
