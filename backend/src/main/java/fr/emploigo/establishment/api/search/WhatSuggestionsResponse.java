package fr.emploigo.establishment.api.search;

import fr.emploigo.establishment.application.search.WhatSuggestions;
import fr.emploigo.establishment.domain.Sector;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

public record WhatSuggestionsResponse(
        @Schema(
                        requiredMode = Schema.RequiredMode.REQUIRED,
                        description = "Métiers groupés par secteur, chacun une seule fois")
                List<JobGroupResponse> jobs,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
                List<SuggestedEstablishmentResponse> establishments) {
    public record JobGroupResponse(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Sector sector,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<String> jobs) {}

    public record SuggestedEstablishmentResponse(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String siret,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Sector sector) {}

    static WhatSuggestionsResponse from(WhatSuggestions suggestions) {
        return new WhatSuggestionsResponse(
                suggestions.jobs().stream()
                        .map(group -> new JobGroupResponse(group.sector(), group.jobs()))
                        .toList(),
                suggestions.establishments().stream()
                        .map(
                                establishment ->
                                        new SuggestedEstablishmentResponse(
                                                establishment.siret().value(),
                                                establishment.name(),
                                                establishment.sector()))
                        .toList());
    }
}
