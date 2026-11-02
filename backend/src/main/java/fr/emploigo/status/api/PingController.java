package fr.emploigo.status.api;

import fr.emploigo.config.ApiOnly;

import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ApiOnly
public class PingController {
    @GetMapping("/api/ping")
    @ApiResponse(
            responseCode = "200",
            description = "API joignable",
            content = @Content(schema = @Schema(implementation = PingResponse.class)))
    @ApiResponse(
            responseCode = "503",
            description = "Vérification de la session indisponible",
            content = @Content(schema = @Schema(implementation = PingErrorResponse.class)))
    public PingResponse ping() {
        return new PingResponse("ok");
    }
}
