package fr.emploigo.establishment.infrastructure.search;

import fr.emploigo.config.ApiOnly;
import fr.emploigo.establishment.application.search.JobMatch;
import fr.emploigo.establishment.application.search.JobRepository;
import fr.emploigo.establishment.domain.Sector;

import jakarta.persistence.EntityManager;

import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
@ApiOnly
class JpaJobRepository implements JobRepository {
    private final EntityManager entities;

    JpaJobRepository(EntityManager entities) {
        this.entities = entities;
    }

    /** A job is shown once, under the sector of most of its published establishments. */
    @Override
    @SuppressWarnings("unchecked")
    public List<JobMatch> findPublished(String text, int limit) {
        List<Object[]> rows =
                entities.createNativeQuery(
                                """
                                WITH matched AS (
                                    SELECT j.rome_code, j.label,
                                           j.label_search LIKE search_text(:text) || '%%'
                                               AS starts,
                                           word_similarity(search_text(:text), j.label_search)
                                               AS score
                                    FROM reference.jobs j
                                    WHERE search_text(:text) <> ''
                                    AND (j.label_search LIKE '%%' || search_text(:text) || '%%'
                                        OR search_text(:text) <%% j.label_search)
                                ),
                                published AS (
                                    SELECT e.activity_nomenclature, e.activity_code, a.sector_code,
                                           count(*) AS establishments
                                    %s%s
                                    GROUP BY e.activity_nomenclature, e.activity_code, a.sector_code
                                ),
                                sectors AS (
                                    SELECT DISTINCT ON (m.label) m.label, m.starts, m.score,
                                           p.sector_code,
                                           sum(sum(p.establishments)) OVER (PARTITION BY m.label)
                                               AS establishments
                                    FROM matched m
                                    JOIN reference.job_activities ja ON ja.rome_code = m.rome_code
                                    JOIN published p
                                        ON p.activity_nomenclature = ja.nomenclature
                                        AND p.activity_code = ja.activity_code
                                    GROUP BY m.label, m.starts, m.score, p.sector_code
                                    ORDER BY m.label, sum(p.establishments) DESC, p.sector_code
                                )
                                SELECT label, sector_code
                                FROM sectors
                                ORDER BY starts DESC, establishments DESC, score DESC, label
                                LIMIT :limit
                                """
                                        .formatted(
                                                PublishedEstablishmentQuery.TABLES,
                                                PublishedEstablishmentQuery.PUBLISHED))
                        .setParameter("text", text)
                        .setParameter("limit", limit)
                        .getResultList();
        return rows.stream()
                .map(row -> new JobMatch((String) row[0], Sector.valueOf((String) row[1])))
                .toList();
    }
}
