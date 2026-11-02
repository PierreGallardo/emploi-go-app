package fr.emploigo.establishment.api.search;

import fr.emploigo.establishment.application.search.PlaceSuggestion;
import fr.emploigo.establishment.domain.TransitMode;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

public record PlaceSuggestionsResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<PlaceSuggestionResponse> places) {
    public record PlaceSuggestionResponse(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) PlaceSuggestion.Kind kind,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String label,
            @Schema(
                            requiredMode = Schema.RequiredMode.REQUIRED,
                            nullable = true,
                            description = "Commune d'une adresse, premières lignes d'un arrêt")
                    String detail,
            @Schema(
                            requiredMode = Schema.RequiredMode.REQUIRED,
                            nullable = true,
                            description = "Mode d'un arrêt")
                    TransitMode mode,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) double longitude,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) double latitude) {}

    static PlaceSuggestionsResponse from(List<PlaceSuggestion> suggestions) {
        return new PlaceSuggestionsResponse(
                suggestions.stream()
                        .map(
                                place ->
                                        new PlaceSuggestionResponse(
                                                place.kind(),
                                                place.label(),
                                                place.detail(),
                                                place.mode(),
                                                place.position().longitude(),
                                                place.position().latitude()))
                        .toList());
    }
}
