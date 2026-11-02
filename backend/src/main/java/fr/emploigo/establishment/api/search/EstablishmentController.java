package fr.emploigo.establishment.api.search;

import fr.emploigo.config.ApiOnly;
import fr.emploigo.establishment.application.search.ListEstablishmentPointsUseCase;
import fr.emploigo.establishment.application.search.ReadEstablishmentUseCase;
import fr.emploigo.establishment.application.search.SearchEstablishmentsUseCase;
import fr.emploigo.establishment.application.search.SuggestWhatUseCase;
import fr.emploigo.establishment.domain.EstablishmentSort;
import fr.emploigo.establishment.domain.SearchCriteria;
import fr.emploigo.establishment.domain.Siret;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springdoc.core.annotations.ParameterObject;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@ApiOnly
@RequestMapping("/api/establishments")
@Tag(name = "Carte", description = "Établissements visibles de la carte, pour les jeunes connectés")
public class EstablishmentController {
    private static final String SIRET = "[0-9]{14}";

    private final SearchEstablishmentsUseCase search;
    private final ListEstablishmentPointsUseCase points;
    private final ReadEstablishmentUseCase detail;
    private final SuggestWhatUseCase suggestions;

    public EstablishmentController(
            SearchEstablishmentsUseCase search,
            ListEstablishmentPointsUseCase points,
            ReadEstablishmentUseCase detail,
            SuggestWhatUseCase suggestions) {
        this.search = search;
        this.points = points;
        this.detail = detail;
        this.suggestions = suggestions;
    }

    @GetMapping
    @Operation(
            summary = "Rechercher les établissements de la carte",
            description =
                    "Une page de la liste et le nombre total. Le texte est cherché sans accent ni"
                            + " casse, en partie de mot ou avec une faute de frappe. Le tri par"
                            + " distance demande le header Search-Area.")
    @ApiResponse(
            responseCode = "200",
            description = "Page de la liste",
            content = @Content(schema = @Schema(implementation = EstablishmentPageResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = "Filtres, page ou zone invalides",
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
    public EstablishmentPageResponse search(
            @ParameterObject @Valid FilterParameters filters,
            @Parameter(description = "Établissements d'un regroupement de points")
                    @RequestParam(name = "siret", required = false)
                    @Size(max = SearchCriteria.MAX_SIRETS)
                    List<@Pattern(regexp = SIRET) String> sirets,
            @RequestParam(defaultValue = "RELEVANCE") EstablishmentSort sort,
            @RequestParam(defaultValue = "0") @Min(0) @Max(1000) int page,
            @RequestParam(defaultValue = "20")
                    @Min(1)
                    @Max(SearchEstablishmentsUseCase.MAX_PAGE_SIZE)
                    int pageSize,
            @Parameter(description = SearchAreaHeader.DESCRIPTION)
                    @RequestHeader(name = SearchAreaHeader.NAME, required = false)
                    String area) {
        Set<Siret> group =
                sirets == null
                        ? Set.of()
                        : sirets.stream().map(Siret::new).collect(Collectors.toSet());
        return EstablishmentPageResponse.from(
                search.search(
                        filters.criteria(SearchAreaHeader.parse(area), group),
                        sort,
                        page,
                        pageSize),
                page,
                pageSize);
    }

    @GetMapping("/points")
    @Operation(
            summary = "Placer sur la carte les établissements d'une recherche",
            description = "Les établissements localisés, avec les filtres de la liste.")
    @ApiResponse(
            responseCode = "200",
            description = "Points de la carte",
            content =
                    @Content(schema = @Schema(implementation = EstablishmentPointsResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = "Filtres ou zone invalides",
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
    public EstablishmentPointsResponse points(
            @ParameterObject @Valid FilterParameters filters,
            @Parameter(description = SearchAreaHeader.DESCRIPTION)
                    @RequestHeader(name = SearchAreaHeader.NAME, required = false)
                    String area) {
        return EstablishmentPointsResponse.from(
                points.list(filters.criteria(SearchAreaHeader.parse(area), Set.of())));
    }

    @GetMapping("/suggestions")
    @Operation(
            summary = "Suggérer des métiers et des établissements pour « Quoi ? »",
            description =
                    "Des métiers exercés par un établissement de la carte, groupés sous un secteur,"
                            + " puis des établissements dont le nom ressemble au texte.")
    @ApiResponse(
            responseCode = "200",
            description = "Suggestions",
            content = @Content(schema = @Schema(implementation = WhatSuggestionsResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = "Texte trop long",
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
    public WhatSuggestionsResponse suggest(
            @RequestParam @Size(max = SearchCriteria.MAX_TEXT_LENGTH) String q) {
        return WhatSuggestionsResponse.from(suggestions.suggest(q));
    }

    @GetMapping("/{siret}")
    @Operation(summary = "Consulter la fiche d'un établissement de la carte")
    @ApiResponse(
            responseCode = "200",
            description = "Fiche",
            content =
                    @Content(schema = @Schema(implementation = EstablishmentDetailResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = "SIRET invalide",
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
            responseCode = "404",
            description = "Établissement absent de la carte",
            content = @Content(schema = @Schema(implementation = SearchErrorResponse.class)))
    @ApiResponse(
            responseCode = "503",
            description = "Carte ou session temporairement indisponible",
            content = @Content(schema = @Schema(implementation = SearchErrorResponse.class)))
    public EstablishmentDetailResponse detail(@PathVariable @Pattern(regexp = SIRET) String siret) {
        return EstablishmentDetailResponse.from(detail.read(new Siret(siret)));
    }
}
