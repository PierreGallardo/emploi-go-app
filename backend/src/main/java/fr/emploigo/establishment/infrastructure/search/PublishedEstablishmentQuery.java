package fr.emploigo.establishment.infrastructure.search;

import fr.emploigo.establishment.domain.SearchArea;
import fr.emploigo.establishment.domain.SearchCriteria;
import fr.emploigo.establishment.domain.Siret;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The SQL of the published establishments that meet some criteria: a clause per criterion given,
 * every value bound as a parameter. The text matches the name, the company name or the activity, as
 * a part of a word or with a typing error; a text without letter nor digit matches nothing.
 */
final class PublishedEstablishmentQuery {
    static final String TABLES =
            """
            FROM establishments e
            JOIN reference.naf_activities a
                ON a.nomenclature = e.activity_nomenclature AND a.code = e.activity_code
            JOIN reference.communes c ON c.code = e.commune_code
            """;

    /** The establishments the map shows: visible, and retained by the import. */
    static final String PUBLISHED = "WHERE e.visible AND e.exclusion_reason IS NULL\n";

    /** A column of a text in its search form, and its weight in the relevance. */
    record TextColumn(String column, double weight) {}

    /** The columns the text of a search is found in: the name counts most. */
    static final List<TextColumn> SEARCHED =
            List.of(
                    new TextColumn("e.name_search", 1),
                    new TextColumn("e.company_search", 0.9),
                    new TextColumn("a.label_search", 0.8));

    /** The columns of an establishment named in « Quoi ? ». */
    static final List<TextColumn> NAMED = SEARCHED.subList(0, 2);

    /** How close the text is to the establishment, from 0 to 1. */
    static final String RELEVANCE = relevance(SEARCHED);

    static final String DISTANCE =
            "ST_Distance(e.location, ST_SetSRID(ST_MakePoint(:longitude, :latitude), 4326)::geography)";

    private final StringBuilder where = new StringBuilder(TABLES + PUBLISHED);
    private final Map<String, Object> parameters = new LinkedHashMap<>();
    private final Optional<SearchArea> area;
    private final boolean text;

    PublishedEstablishmentQuery(SearchCriteria criteria) {
        area = criteria.area();
        text = !criteria.text().isEmpty();
        if (text) {
            where.append(textMatches(SEARCHED));
            parameters.put("text", criteria.text());
        }
        criteria.job()
                .ifPresent(
                        job -> {
                            where.append(
                                    """
                                    AND EXISTS (
                                        SELECT 1
                                        FROM reference.jobs j
                                        JOIN reference.job_activities ja ON ja.rome_code = j.rome_code
                                        WHERE j.label = :job
                                        AND ja.nomenclature = e.activity_nomenclature
                                        AND ja.activity_code = e.activity_code)
                                    """);
                            parameters.put("job", job);
                        });
        if (!criteria.sectors().isEmpty()) {
            where.append("AND a.sector_code IN (:sectors)\n");
            parameters.put("sectors", names(criteria.sectors()));
        }
        if (!criteria.companyTypes().isEmpty()) {
            where.append("AND e.company_type IN (:companyTypes)\n");
            parameters.put("companyTypes", names(criteria.companyTypes()));
        }
        if (!criteria.sizes().isEmpty()) {
            where.append("AND e.size IN (:sizes)\n");
            parameters.put("sizes", names(criteria.sizes()));
        }
        if (criteria.nearTransit()) {
            where.append(
                    """
                    AND EXISTS (
                        SELECT 1 FROM transit_stops s
                        WHERE ST_DWithin(s.location, e.location, :transitMeters))
                    """);
            parameters.put("transitMeters", SearchCriteria.TRANSIT_METERS);
        }
        area.ifPresent(
                circle -> {
                    where.append(
                            """
                            AND ST_DWithin(
                                e.location,
                                ST_SetSRID(ST_MakePoint(:longitude, :latitude), 4326)::geography,
                                :radiusMeters)
                            """);
                    parameters.put("longitude", circle.centre().longitude());
                    parameters.put("latitude", circle.centre().latitude());
                    parameters.put("radiusMeters", circle.radiusMeters());
                });
        if (!criteria.sirets().isEmpty()) {
            where.append("AND e.siret IN (:sirets)\n");
            parameters.put("sirets", criteria.sirets().stream().map(Siret::value).toList());
        }
    }

    boolean hasText() {
        return text;
    }

    boolean hasArea() {
        return area.isPresent();
    }

    /** The FROM and WHERE clauses. */
    String clauses() {
        return where.toString();
    }

    /**
     * Binds the parameters of the clauses, and those of {@link #RELEVANCE} and {@link #DISTANCE}
     * when the criteria give them.
     */
    Query bind(EntityManager entities, String sql) {
        Query query = entities.createNativeQuery(sql);
        parameters.forEach(query::setParameter);
        return query;
    }

    private static List<String> names(Set<? extends Enum<?>> values) {
        return values.stream().map(Enum::name).toList();
    }

    /**
     * The clause of a text found in one of the columns, as a part of a word or with a typing error;
     * a text without letter nor digit matches nothing.
     */
    static String textMatches(List<TextColumn> columns) {
        return columns.stream()
                .map(
                        column ->
                                "%1$s LIKE '%%' || search_text(:text) || '%%'\n"
                                                .formatted(column.column())
                                        + "    OR search_text(:text) <%% %1$s"
                                                .formatted(column.column()))
                .collect(
                        Collectors.joining(
                                "\n    OR ", "AND search_text(:text) <> ''\nAND (", ")\n"));
    }

    /** How close the text is to the columns, from 0 to 1, each by its weight. */
    static String relevance(List<TextColumn> columns) {
        return columns.stream()
                .map(
                        column ->
                                "%s * word_similarity(search_text(:text), %s)"
                                        .formatted(column.weight(), column.column()))
                .collect(Collectors.joining(",\n    ", "greatest(\n    ", ")\n"));
    }
}
