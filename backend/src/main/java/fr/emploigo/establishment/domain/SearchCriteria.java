package fr.emploigo.establishment.domain;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * What the map looks for among the published establishments; an empty set or text filters nothing.
 * {@code job} is the label of a job, {@code sirets} the establishments of a group of points.
 */
public record SearchCriteria(
        String text,
        Optional<String> job,
        Set<Sector> sectors,
        Set<CompanyType> companyTypes,
        Set<EstablishmentSize> sizes,
        boolean nearTransit,
        Optional<SearchArea> area,
        Set<Siret> sirets) {
    public static final int MAX_TEXT_LENGTH = 100;

    /** The longest label of a job the reference data accept. */
    public static final int MAX_JOB_LENGTH = 255;

    /** The points of a group the map opens: as many as it reads at once. */
    public static final int MAX_SIRETS = 200;

    /** An establishment is « accessible en transports » with a Divia stop this close. */
    public static final int TRANSIT_METERS = 300;

    public SearchCriteria {
        text = Objects.requireNonNull(text).strip();
        if (text.length() > MAX_TEXT_LENGTH) throw new IllegalArgumentException("Text too long.");
        Objects.requireNonNull(job);
        Objects.requireNonNull(area);
        sectors = Set.copyOf(sectors);
        companyTypes = Set.copyOf(companyTypes);
        sizes = Set.copyOf(sizes);
        sirets = Set.copyOf(sirets);
        if (sirets.size() > MAX_SIRETS) throw new IllegalArgumentException("Too many SIRET.");
    }
}
