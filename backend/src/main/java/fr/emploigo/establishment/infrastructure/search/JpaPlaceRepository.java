package fr.emploigo.establishment.infrastructure.search;

import fr.emploigo.config.ApiOnly;
import fr.emploigo.establishment.application.search.CommuneMatch;
import fr.emploigo.establishment.application.search.NumberMatch;
import fr.emploigo.establishment.application.search.PlaceRepository;
import fr.emploigo.establishment.application.search.StreetMatch;
import fr.emploigo.establishment.domain.Position;
import fr.emploigo.establishment.domain.TransitStop;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

import org.springframework.stereotype.Repository;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Repository
@ApiOnly
class JpaPlaceRepository implements PlaceRepository {
    /** More words than this are a sentence, not a place. */
    static final int MAX_WORDS = 8;

    private final EntityManager entities;

    JpaPlaceRepository(EntityManager entities) {
        this.entities = entities;
    }

    @Override
    public List<CommuneMatch> findCommunes(String words, int limit) {
        List<String> typed = split(words);
        return rows(
                        bind(
                                        """
                                        SELECT c.name, c.longitude, c.latitude
                                        FROM reference.communes c
                                        WHERE %s
                                        ORDER BY %s, c.name
                                        LIMIT :limit
                                        """
                                                .formatted(
                                                        every("search_text(c.name)", typed),
                                                        rank("search_text(c.name)")),
                                        typed)
                                .setParameter("limit", limit))
                .stream()
                .map(
                        row ->
                                new CommuneMatch(
                                        (String) row[0],
                                        new Position((Double) row[1], (Double) row[2])))
                .toList();
    }

    /** The first word is looked for in the name of the street, where it has its index. */
    @Override
    public List<StreetMatch> findStreets(String words, int limit) {
        List<String> typed = split(words);
        return rows(
                        bind(
                                        """
                                        SELECT s.id, s.name, c.name, s.longitude, s.latitude
                                        FROM address_streets s
                                        JOIN reference.communes c ON c.code = s.commune_code
                                        WHERE search_text(s.name) LIKE '%%' || search_text(:word0) || '%%'
                                        AND %s
                                        ORDER BY %s, s.name, c.name
                                        LIMIT :limit
                                        """
                                                .formatted(
                                                        every(
                                                                "search_text(s.name || ' ' || c.name)",
                                                                typed),
                                                        rank("search_text(s.name)")),
                                        typed)
                                .setParameter("limit", limit))
                .stream()
                .map(
                        row ->
                                new StreetMatch(
                                        (Integer) row[0],
                                        (String) row[1],
                                        (String) row[2],
                                        new Position((Double) row[3], (Double) row[4])))
                .toList();
    }

    @Override
    public List<NumberMatch> findNumbers(
            Collection<Integer> streetIds, int number, Optional<String> suffix) {
        if (streetIds.isEmpty()) return List.of();
        Query query =
                entities.createNativeQuery(
                                """
                                SELECT n.street_id, n.label, n.longitude, n.latitude
                                FROM address_numbers n
                                WHERE n.street_id IN (:streets) AND n.number = :number
                                %s
                                ORDER BY n.street_id, n.label
                                """
                                        .formatted(
                                                suffix.isPresent() ? "AND n.label = :label" : ""))
                        .setParameter("streets", streetIds)
                        .setParameter("number", number);
        suffix.ifPresent(index -> query.setParameter("label", number + " " + index));
        return rows(query).stream()
                .map(
                        row ->
                                new NumberMatch(
                                        (Integer) row[0],
                                        (String) row[1],
                                        new Position((Double) row[2], (Double) row[3])))
                .toList();
    }

    /** A tram stop comes before a bus stop of the same rank. */
    @Override
    public List<TransitStop> findStops(String words, int limit) {
        List<String> typed = split(words);
        return TransitStopRows.stops(
                bind(
                                """
                                WITH found AS (
                                    SELECT s.id, %s AS rank, s.mode = 'TRAM' AS tram
                                    FROM transit_stops s
                                    WHERE %s
                                    ORDER BY rank, tram DESC, s.name, s.id
                                    LIMIT :limit
                                )
                                SELECT s.id, s.name, s.longitude, s.latitude, l.name, l.mode
                                FROM found
                                JOIN transit_stops s ON s.id = found.id
                                JOIN transit_stop_lines sl ON sl.stop_id = s.id
                                JOIN transit_lines l ON l.name = sl.line_name
                                ORDER BY found.rank, found.tram DESC, s.name, s.id
                                """
                                        .formatted(
                                                rank("search_text(s.name)"),
                                                every("search_text(s.name)", typed)),
                                typed)
                        .setParameter("limit", limit)
                        .getResultList());
    }

    /** The words of a text, as the search form splits them. */
    private static List<String> split(String words) {
        return Arrays.stream(words.split("[^\\p{L}\\p{N}]+"))
                .filter(word -> !word.isEmpty())
                .limit(MAX_WORDS)
                .toList();
    }

    /** A word without letter nor digit of the search form matches nothing. */
    private static String every(String column, List<String> words) {
        return IntStream.range(0, words.size())
                .mapToObj(
                        index ->
                                "search_text(:word%1$d) <> '' AND %2$s LIKE '%%' || search_text(:word%1$d) || '%%'"
                                        .formatted(index, column))
                .collect(Collectors.joining(" AND "));
    }

    /** 0 when the name starts with the first word, 1 when one of its words does, 2 otherwise. */
    private static String rank(String column) {
        return """
               CASE WHEN %1$s LIKE search_text(:word0) || '%%' THEN 0
                    WHEN %1$s LIKE '%%' || ' ' || search_text(:word0) || '%%' THEN 1
                    ELSE 2 END
               """
                .formatted(column);
    }

    private Query bind(String sql, List<String> words) {
        Query query = entities.createNativeQuery(sql);
        IntStream.range(0, words.size())
                .forEach(index -> query.setParameter("word" + index, words.get(index)));
        return query;
    }

    @SuppressWarnings("unchecked")
    private static List<Object[]> rows(Query query) {
        return query.getResultList();
    }
}
