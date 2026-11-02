package fr.emploigo.establishment.application.search;

import fr.emploigo.config.ApiOnly;
import fr.emploigo.establishment.domain.Sector;
import fr.emploigo.establishment.domain.SuggestedText;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The suggestions of « Quoi ? » for what a youth is typing. */
@Service
@ApiOnly
public class SuggestWhatUseCase {
    static final int MAX_JOBS = 8;
    static final int MAX_ESTABLISHMENTS = 5;

    private final JobRepository jobs;
    private final PublishedEstablishmentRepository establishments;

    public SuggestWhatUseCase(JobRepository jobs, PublishedEstablishmentRepository establishments) {
        this.jobs = jobs;
        this.establishments = establishments;
    }

    /** The groups keep the order of their best job. */
    @Transactional(readOnly = true)
    public WhatSuggestions suggest(String text) {
        if (!SuggestedText.isLongEnough(text)) return new WhatSuggestions(List.of(), List.of());
        Map<Sector, List<String>> bySector = new LinkedHashMap<>();
        for (JobMatch job : jobs.findPublished(text, MAX_JOBS))
            bySector.computeIfAbsent(job.sector(), sector -> new ArrayList<>()).add(job.label());
        return new WhatSuggestions(
                bySector.entrySet().stream()
                        .map(
                                group ->
                                        new WhatSuggestions.JobGroup(
                                                group.getKey(), group.getValue()))
                        .toList(),
                establishments.findNamed(text, MAX_ESTABLISHMENTS).stream()
                        .map(EstablishmentMatch::shown)
                        .toList());
    }
}
