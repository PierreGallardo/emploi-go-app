package fr.emploigo.establishment.api.search;

import fr.emploigo.config.ApiOnly;
import fr.emploigo.establishment.application.search.ReadDatasetsUseCase;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ApiOnly
@Tag(name = "Carte")
public class DatasetsController {
    private final ReadDatasetsUseCase datasets;

    public DatasetsController(ReadDatasetsUseCase datasets) {
        this.datasets = datasets;
    }

    @GetMapping("/api/datasets")
    @Operation(
            summary = "Lire les arrêts Divia et les dates des données ouvertes de la carte",
            description =
                    "DiviaMobilités / Dijon Métropole et Base Adresse Nationale, Licence Ouverte 2.0 :"
                            + " leur mention cite la date de publication.")
    @ApiResponse(
            responseCode = "200",
            description = "Arrêts et dates",
            content = @Content(schema = @Schema(implementation = DatasetsResponse.class)))
    @ApiResponse(
            responseCode = "401",
            description = "Authentification nécessaire",
            content = @Content(schema = @Schema(implementation = SearchErrorResponse.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Réservé aux jeunes",
            content = @Content(schema = @Schema(implementation = SearchErrorResponse.class)))
    @ApiResponse(
            responseCode = "503",
            description = "Carte ou session temporairement indisponible",
            content = @Content(schema = @Schema(implementation = SearchErrorResponse.class)))
    public DatasetsResponse read() {
        return DatasetsResponse.from(datasets.read());
    }
}
