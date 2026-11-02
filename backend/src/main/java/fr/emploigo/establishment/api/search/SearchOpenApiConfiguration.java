package fr.emploigo.establishment.api.search;

import fr.emploigo.config.ApiOnly;

import io.swagger.v3.oas.models.media.JsonSchema;

import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ApiOnly
class SearchOpenApiConfiguration {
    @Bean
    OpenApiCustomizer nullableNearestStop() {
        return document -> {
            var detail = document.getComponents().getSchemas().get("EstablishmentDetailResponse");
            if (detail == null)
                throw new IllegalStateException("EstablishmentDetailResponse absent du contrat.");
            // swagger-core writes a nullable reference as a reference of type null: the union of
            // OAS 3.1 is written out instead.
            var stop = new JsonSchema();
            stop.setDescription("Arrêt Divia le plus proche ; null sans position");
            stop.addOneOfItem(new JsonSchema().$ref("#/components/schemas/NearestStopResponse"));
            stop.addOneOfItem(new JsonSchema().typesItem("null"));
            detail.addProperty("nearestStop", stop);
        };
    }
}
