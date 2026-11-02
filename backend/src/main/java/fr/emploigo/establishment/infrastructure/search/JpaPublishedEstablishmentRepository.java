package fr.emploigo.establishment.infrastructure.search;

import static fr.emploigo.establishment.infrastructure.search.PublishedEstablishmentQuery.DISTANCE;
import static fr.emploigo.establishment.infrastructure.search.PublishedEstablishmentQuery.RELEVANCE;

import fr.emploigo.config.ApiOnly;
import fr.emploigo.establishment.application.search.EstablishmentDetail;
import fr.emploigo.establishment.application.search.EstablishmentMatch;
import fr.emploigo.establishment.application.search.EstablishmentPage;
import fr.emploigo.establishment.application.search.EstablishmentPoint;
import fr.emploigo.establishment.application.search.EstablishmentSummary;
import fr.emploigo.establishment.application.search.NearestStop;
import fr.emploigo.establishment.application.search.PublishedEstablishmentRepository;
import fr.emploigo.establishment.domain.EstablishmentSort;
import fr.emploigo.establishment.domain.Position;
import fr.emploigo.establishment.domain.SearchCriteria;
import fr.emploigo.establishment.domain.Sector;
import fr.emploigo.establishment.domain.Siret;
import fr.emploigo.establishment.domain.TransitMode;

import jakarta.persistence.EntityManager;

import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** The establishments of the map, read in SQL: the search needs PostGIS and pg_trgm. */
@Repository
@ApiOnly
class JpaPublishedEstablishmentRepository implements PublishedEstablishmentRepository {
    static final int JOBS_OF_A_SUMMARY = 4;
    static final int JOBS_OF_A_DETAIL = 12;
    private static final String BY_NAME = "e.name_search, e.siret";

    private final EntityManager entities;

    JpaPublishedEstablishmentRepository(EntityManager entities) {
        this.entities = entities;
    }

    /** An activity of the NAF, as {@code reference.job_activities} keys it. */
    private record Activity(String nomenclature, String code) {}

    @Override
    public EstablishmentPage search(
            SearchCriteria criteria, EstablishmentSort sort, int page, int size) {
        var query = new PublishedEstablishmentQuery(criteria);
        long total =
                ((Number)
                                query.bind(entities, "SELECT count(*) " + query.clauses())
                                        .getSingleResult())
                        .longValue();
        String order =
                switch (sort) {
                    case RELEVANCE -> query.hasText() ? RELEVANCE + " DESC, " + BY_NAME : BY_NAME;
                    case DISTANCE -> DISTANCE + ", e.siret";
                    case NAME -> BY_NAME;
                };
        List<Object[]> rows =
                rows(
                        query.bind(
                                        entities,
                                        """
                                        SELECT e.siret, e.name, e.company_name, a.sector_code,
                                               c.name, e.longitude, e.latitude, e.description,
                                               e.activity_nomenclature, e.activity_code, %s,
                                               e.image_file
                                        %s
                                        ORDER BY %s
                                        LIMIT :size OFFSET :offset
                                        """
                                                .formatted(
                                                        query.hasArea()
                                                                ? "round(" + DISTANCE + ")::int"
                                                                : "NULL::int",
                                                        query.clauses(),
                                                        order))
                                .setParameter("size", size)
                                .setParameter("offset", page * size)
                                .getResultList());
        Map<Activity, List<String>> jobs =
                jobsOf(
                        rows.stream()
                                .map(row -> new Activity((String) row[8], (String) row[9]))
                                .collect(Collectors.toSet()),
                        JOBS_OF_A_SUMMARY);
        return new EstablishmentPage(
                rows.stream()
                        .map(
                                row ->
                                        new EstablishmentSummary(
                                                new Siret((String) row[0]),
                                                (String) row[1],
                                                (String) row[2],
                                                Sector.valueOf((String) row[3]),
                                                (String) row[4],
                                                position(row[5], row[6]),
                                                (String) row[7],
                                                (String) row[11],
                                                jobs.getOrDefault(
                                                        new Activity(
                                                                (String) row[8], (String) row[9]),
                                                        List.of()),
                                                (Integer) row[10]))
                        .toList(),
                total);
    }

    @Override
    public List<EstablishmentPoint> findPoints(SearchCriteria criteria, int limit) {
        var query = new PublishedEstablishmentQuery(criteria);
        List<Object[]> rows =
                rows(
                        query.bind(
                                        entities,
                                        """
                                        SELECT e.siret, e.longitude, e.latitude, a.sector_code
                                        %s
                                        AND e.location IS NOT NULL
                                        ORDER BY e.siret
                                        LIMIT :limit
                                        """
                                                .formatted(query.clauses()))
                                .setParameter("limit", limit)
                                .getResultList());
        return rows.stream()
                .map(
                        row ->
                                new EstablishmentPoint(
                                        new Siret((String) row[0]),
                                        new Position((Double) row[1], (Double) row[2]),
                                        Sector.valueOf((String) row[3])))
                .toList();
    }

    @Override
    public Optional<EstablishmentDetail> findDetail(Siret siret) {
        List<Object[]> rows =
                rows(
                        entities.createNativeQuery(
                                        """
                                        SELECT e.siret, e.name, e.company_name, a.sector_code,
                                               a.label, e.address, c.name, e.longitude, e.latitude,
                                               e.description, e.jobs_url, e.headcount_range,
                                               e.company_category,
                                               EXTRACT(YEAR FROM e.company_created_on)::int,
                                               e.ess,
                                               e.activity_nomenclature, e.activity_code,
                                               stop.name, stop.mode, round(stop.distance)::int,
                                               e.image_file
                                        %s
                                        LEFT JOIN LATERAL (
                                            SELECT s.name, s.mode,
                                                   ST_Distance(s.location, e.location) AS distance
                                            FROM transit_stops s
                                            WHERE e.location IS NOT NULL
                                            ORDER BY s.location <-> e.location, s.id
                                            LIMIT 1
                                        ) stop ON TRUE
                                        %s
                                        AND e.siret = :siret
                                        """
                                                .formatted(
                                                        PublishedEstablishmentQuery.TABLES,
                                                        PublishedEstablishmentQuery.PUBLISHED))
                                .setParameter("siret", siret.value())
                                .getResultList());
        if (rows.isEmpty()) return Optional.empty();
        Object[] row = rows.getFirst();
        Activity activity = new Activity((String) row[15], (String) row[16]);
        return Optional.of(
                new EstablishmentDetail(
                        new Siret((String) row[0]),
                        (String) row[1],
                        (String) row[2],
                        Sector.valueOf((String) row[3]),
                        (String) row[4],
                        (String) row[5],
                        (String) row[6],
                        position(row[7], row[8]),
                        (String) row[9],
                        (String) row[20],
                        (String) row[10],
                        jobsOf(Set.of(activity), JOBS_OF_A_DETAIL)
                                .getOrDefault(activity, List.of()),
                        (String) row[11],
                        (String) row[12],
                        (Integer) row[13],
                        (Boolean) row[14],
                        row[17] == null
                                ? null
                                : new NearestStop(
                                        (String) row[17],
                                        TransitMode.valueOf((String) row[18]),
                                        (Integer) row[19])));
    }

    @Override
    public List<EstablishmentMatch> findNamed(String text, int limit) {
        List<Object[]> rows =
                rows(
                        entities.createNativeQuery(
                                        """
                                        SELECT e.siret, e.name, e.company_name, a.sector_code
                                        %s%s%s
                                        ORDER BY %s DESC, %s
                                        LIMIT :limit
                                        """
                                                .formatted(
                                                        PublishedEstablishmentQuery.TABLES,
                                                        PublishedEstablishmentQuery.PUBLISHED,
                                                        PublishedEstablishmentQuery.textMatches(
                                                                PublishedEstablishmentQuery.NAMED),
                                                        PublishedEstablishmentQuery.relevance(
                                                                PublishedEstablishmentQuery.NAMED),
                                                        BY_NAME))
                                .setParameter("text", text)
                                .setParameter("limit", limit)
                                .getResultList());
        return rows.stream()
                .map(
                        row ->
                                new EstablishmentMatch(
                                        new Siret((String) row[0]),
                                        (String) row[1],
                                        (String) row[2],
                                        Sector.valueOf((String) row[3])))
                .toList();
    }

    /** The first jobs of each activity, by label, in one query. */
    private Map<Activity, List<String>> jobsOf(Set<Activity> activities, int perActivity) {
        if (activities.isEmpty()) return Map.of();
        List<Object[]> rows =
                rows(
                        entities.createNativeQuery(
                                        """
                                        SELECT nomenclature, activity_code, label
                                        FROM (
                                            SELECT ja.nomenclature, ja.activity_code, j.label,
                                                   row_number() OVER (
                                                       PARTITION BY ja.nomenclature, ja.activity_code
                                                       ORDER BY j.label) AS rank
                                            FROM reference.job_activities ja
                                            JOIN reference.jobs j ON j.rome_code = ja.rome_code
                                            WHERE ja.nomenclature IN (:nomenclatures)
                                            AND ja.activity_code IN (:codes)
                                        ) ranked
                                        WHERE rank <= :perActivity
                                        ORDER BY label
                                        """)
                                .setParameter(
                                        "nomenclatures", values(activities, Activity::nomenclature))
                                .setParameter("codes", values(activities, Activity::code))
                                .setParameter("perActivity", perActivity)
                                .getResultList());
        Map<Activity, List<String>> jobs = new HashMap<>();
        for (Object[] row : rows) {
            Activity activity = new Activity((String) row[0], (String) row[1]);
            if (activities.contains(activity))
                jobs.computeIfAbsent(activity, key -> new ArrayList<>()).add((String) row[2]);
        }
        return jobs;
    }

    private static List<String> values(
            Collection<Activity> activities, Function<Activity, String> part) {
        return activities.stream().map(part).distinct().toList();
    }

    private static Position position(Object longitude, Object latitude) {
        return longitude == null ? null : new Position((Double) longitude, (Double) latitude);
    }

    @SuppressWarnings("unchecked")
    private static List<Object[]> rows(List<?> results) {
        return (List<Object[]>) results;
    }
}
