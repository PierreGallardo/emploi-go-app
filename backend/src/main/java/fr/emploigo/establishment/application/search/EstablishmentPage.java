package fr.emploigo.establishment.application.search;

import java.util.List;

/** One page of a search, and how many establishments the whole search finds. */
public record EstablishmentPage(List<EstablishmentSummary> items, long total) {
    public EstablishmentPage {
        items = List.copyOf(items);
    }
}
