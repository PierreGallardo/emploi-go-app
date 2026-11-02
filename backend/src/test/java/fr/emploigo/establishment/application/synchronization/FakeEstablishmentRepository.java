package fr.emploigo.establishment.application.synchronization;

import fr.emploigo.establishment.domain.Assessment;
import fr.emploigo.establishment.domain.Establishment;
import fr.emploigo.establishment.domain.ExclusionReason;
import fr.emploigo.establishment.domain.Siret;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The establishments table, with the rules of the database that the import relies on. */
public final class FakeEstablishmentRepository implements EstablishmentRepository {
    public record Row(
            Establishment establishment,
            ExclusionReason exclusion,
            Instant excludedAt,
            Instant seenAt) {}

    private final Map<Siret, Row> rows = new LinkedHashMap<>();

    public FakeEstablishmentRepository with(Establishment establishment, Instant seenAt) {
        rows.put(establishment.siret(), new Row(establishment, null, null, seenAt));
        return this;
    }

    public FakeEstablishmentRepository excluded(Establishment establishment, Instant excludedAt) {
        rows.put(
                establishment.siret(),
                new Row(establishment, ExclusionReason.NOT_FOUND, excludedAt, excludedAt));
        return this;
    }

    public Map<Siret, Row> rows() {
        return rows;
    }

    @Override
    public void save(List<Assessment> assessments, Instant seenAt) {
        for (Assessment assessment : assessments) {
            switch (assessment) {
                case Assessment.Retained retained ->
                        rows.put(
                                retained.establishment().siret(),
                                new Row(retained.establishment(), null, null, seenAt));
                case Assessment.Excluded excluded ->
                        rows.computeIfPresent(
                                excluded.siret(),
                                (siret, row) ->
                                        new Row(
                                                row.establishment(),
                                                excluded.reason(),
                                                firstExclusion(row, seenAt),
                                                seenAt));
                case Assessment.Withheld withheld -> rows.remove(withheld.siret());
            }
        }
    }

    @Override
    public int markNotFound(Instant seenBefore) {
        int marked = 0;
        for (var entry : rows.entrySet()) {
            Row row = entry.getValue();
            if (row.seenAt().isBefore(seenBefore)
                    && row.exclusion() != ExclusionReason.NOT_FOUND) {
                entry.setValue(
                        new Row(
                                row.establishment(),
                                ExclusionReason.NOT_FOUND,
                                firstExclusion(row, seenBefore),
                                row.seenAt()));
                marked++;
            }
        }
        return marked;
    }

    @Override
    public int deleteExcluded(Instant excludedBefore, int limit) {
        List<Siret> due =
                rows.values().stream()
                        .filter(row -> row.excludedAt() != null)
                        .filter(row -> row.excludedAt().isBefore(excludedBefore))
                        .sorted(Comparator.comparing(Row::excludedAt))
                        .limit(limit)
                        .map(row -> row.establishment().siret())
                        .toList();
        due.forEach(rows::remove);
        return due.size();
    }

    @Override
    public boolean isEmpty() {
        return rows.isEmpty();
    }

    private static Instant firstExclusion(Row row, Instant now) {
        return row.excludedAt() == null ? now : row.excludedAt();
    }
}
