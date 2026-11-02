package fr.emploigo.establishment.application.search;

import java.util.List;

/** The jobs of the ROME / NAF table of France Travail. */
public interface JobRepository {
    /**
     * The jobs whose label looks like {@code text} and that some published establishment does, at
     * most {@code limit}: those whose label starts with the text first, then the jobs of the most
     * establishments.
     */
    List<JobMatch> findPublished(String text, int limit);
}
