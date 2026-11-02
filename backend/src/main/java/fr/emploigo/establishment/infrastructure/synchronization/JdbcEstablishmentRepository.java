package fr.emploigo.establishment.infrastructure.synchronization;

import fr.emploigo.config.WorkerOnly;
import fr.emploigo.establishment.application.synchronization.EstablishmentRepository;
import fr.emploigo.establishment.domain.Assessment;
import fr.emploigo.establishment.domain.Establishment;
import fr.emploigo.establishment.domain.ExclusionReason;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The writes of the import, in JDBC batches: one round trip per kind of assessment of a page, and
 * no read before a write. Only the State data are written, never the columns we fill ourselves.
 */
@Repository
@WorkerOnly
class JdbcEstablishmentRepository implements EstablishmentRepository {
    private static final String RETAIN =
            """
            INSERT INTO establishments (
                siret, name, company_name, address, commune_code, longitude, latitude,
                activity_nomenclature, activity_code, company_type, headcount_range,
                company_category, company_created_on, ess, seen_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (siret) DO UPDATE SET
                (name, company_name, address, commune_code, longitude, latitude,
                 activity_nomenclature, activity_code, company_type, headcount_range,
                 company_category, company_created_on, ess, seen_at, exclusion_reason,
                 excluded_at)
                = (EXCLUDED.name, EXCLUDED.company_name, EXCLUDED.address, EXCLUDED.commune_code,
                   EXCLUDED.longitude, EXCLUDED.latitude, EXCLUDED.activity_nomenclature,
                   EXCLUDED.activity_code, EXCLUDED.company_type, EXCLUDED.headcount_range,
                   EXCLUDED.company_category, EXCLUDED.company_created_on,
                   EXCLUDED.ess, EXCLUDED.seen_at, NULL, NULL)
            """;

    // An establishment excluded again keeps the date of its first exclusion.
    private static final String EXCLUDE =
            """
            UPDATE establishments
            SET exclusion_reason = ?, excluded_at = COALESCE(excluded_at, ?), seen_at = ?
            WHERE siret = ?
            """;

    private final JdbcTemplate jdbc;

    JdbcEstablishmentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public void save(List<Assessment> assessments, Instant seenAt) {
        Timestamp seen = Timestamp.from(seenAt);
        List<Object[]> retained = new ArrayList<>();
        List<Object[]> excluded = new ArrayList<>();
        List<Object[]> withheld = new ArrayList<>();
        for (Assessment assessment : assessments) {
            switch (assessment) {
                case Assessment.Retained kept -> retained.add(row(kept.establishment(), seen));
                case Assessment.Excluded hidden ->
                        excluded.add(
                                new Object[] {
                                    hidden.reason().name(), seen, seen, hidden.siret().value()
                                });
                case Assessment.Withheld opposed ->
                        withheld.add(new Object[] {opposed.siret().value()});
            }
        }
        if (!retained.isEmpty()) jdbc.batchUpdate(RETAIN, retained);
        if (!excluded.isEmpty()) jdbc.batchUpdate(EXCLUDE, excluded);
        if (!withheld.isEmpty())
            jdbc.batchUpdate("DELETE FROM establishments WHERE siret = ?", withheld);
    }

    @Override
    @Transactional
    public int markNotFound(Instant seenBefore) {
        return jdbc.update(
                """
                UPDATE establishments
                SET exclusion_reason = ?, excluded_at = COALESCE(excluded_at, ?)
                WHERE seen_at < ?
                AND exclusion_reason IS DISTINCT FROM ?
                """,
                ExclusionReason.NOT_FOUND.name(),
                Timestamp.from(seenBefore),
                Timestamp.from(seenBefore),
                ExclusionReason.NOT_FOUND.name());
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isEmpty() {
        return Boolean.FALSE.equals(
                jdbc.queryForObject(
                        """
                        SELECT EXISTS (SELECT 1 FROM establishments)
                        """,
                        Boolean.class));
    }

    @Override
    @Transactional
    public int deleteExcluded(Instant excludedBefore, int limit) {
        return jdbc.update(
                """
                DELETE FROM establishments
                WHERE siret IN (
                    SELECT siret
                    FROM establishments
                    WHERE excluded_at < ?
                    ORDER BY excluded_at
                    LIMIT ?)
                """,
                Timestamp.from(excludedBefore),
                limit);
    }

    private static Object[] row(Establishment establishment, Timestamp seenAt) {
        return new Object[] {
            establishment.siret().value(),
            establishment.name(),
            establishment.companyName(),
            establishment.address(),
            establishment.communeCode(),
            establishment.position() == null ? null : establishment.position().longitude(),
            establishment.position() == null ? null : establishment.position().latitude(),
            establishment.activity().nomenclature().name(),
            establishment.activity().code(),
            establishment.companyType().name(),
            establishment.headcountRange() == null ? null : establishment.headcountRange().code(),
            establishment.companyCategory(),
            establishment.companyCreatedOn() == null
                    ? null
                    : Date.valueOf(establishment.companyCreatedOn()),
            establishment.ess(),
            seenAt
        };
    }
}
