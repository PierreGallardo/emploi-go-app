package fr.emploigo.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration(proxyBeanMethods = false)
@ApiOnly
public class OpenApiConfiguration {
    @Bean
    OpenAPI emploiGoOpenApi() {
        return new OpenAPI()
                .info(new Info().title("Emploi Go API").version("1"))
                .servers(List.of(new Server().url("/")));
    }
}
