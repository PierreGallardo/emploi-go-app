package fr.emploigo.establishment.api.search;

import fr.emploigo.config.ApiOnly;
import fr.emploigo.establishment.application.search.InvalidSearchException;
import fr.emploigo.establishment.application.search.SuggestPlacesUseCase;
import fr.emploigo.establishment.domain.PlaceQuery;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

@RestController
@ApiOnly
@Tag(name = "Carte")
public class PlaceController {
    /** What a youth types in « Où ? » may be where they live: it stays out of the URL too. */
    static final String QUERY_HEADER = "Place-Query";

    private final SuggestPlacesUseCase places;

    public PlaceController(SuggestPlacesUseCase places) {
        this.places = places;
    }

    @GetMapping("/api/places/suggestions")
    @Operation(
            summary = "Suggérer des lieux pour « Où ? »",
            description =
                    "Communes de la métropole, adresses de la Base Adresse Nationale et arrêts"
                            + " Divia, sans accent ni casse. Un numéro en tête donne les adresses de"
                            + " ce numéro.")
    @ApiResponse(
            responseCode = "200",
            description = "Suggestions",
            content = @Content(schema = @Schema(implementation = PlaceSuggestionsResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = "Texte illisible ou trop long",
            content = @Content(schema = @Schema(implementation = SearchErrorResponse.class)))
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
    public PlaceSuggestionsResponse suggest(
            @Parameter(description = "Texte tapé, encodé comme un composant d'URL (UTF-8)")
                    @RequestHeader(QUERY_HEADER)
                    String query) {
        return PlaceSuggestionsResponse.from(places.suggest(decoded(query)));
    }

    private static String decoded(String query) {
        String text;
        try {
            text = URLDecoder.decode(query, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException unreadable) {
            throw new InvalidSearchException("Unreadable place query.");
        }
        if (text.length() > PlaceQuery.MAX_LENGTH)
            throw new InvalidSearchException("Place query too long.");
        return text;
    }
}
