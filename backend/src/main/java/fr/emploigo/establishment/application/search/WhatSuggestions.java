package fr.emploigo.establishment.application.search;

import fr.emploigo.establishment.domain.Sector;

import java.util.List;

/** The suggestions of « Quoi ? »: jobs grouped by sector, each once, then establishments. */
public record WhatSuggestions(List<JobGroup> jobs, List<EstablishmentMatch> establishments) {
    public record JobGroup(Sector sector, List<String> jobs) {
        public JobGroup {
            jobs = List.copyOf(jobs);
        }
    }

    public WhatSuggestions {
        jobs = List.copyOf(jobs);
        establishments = List.copyOf(establishments);
    }
}
