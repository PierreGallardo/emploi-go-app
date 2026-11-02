package fr.emploigo.establishment.infrastructure.search;

import fr.emploigo.config.ApiOnly;
import fr.emploigo.establishment.application.search.Datasets;
import fr.emploigo.establishment.application.search.DatasetsRepository;
import fr.emploigo.establishment.application.search.StopLocation;
import fr.emploigo.establishment.domain.Position;
import fr.emploigo.establishment.domain.TransitMode;

import jakarta.persistence.EntityManager;

import org.hibernate.query.NativeQuery;
import org.hibernate.type.StandardBasicTypes;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Repository
@ApiOnly
class JpaDatasetsRepository implements DatasetsRepository {
    private final EntityManager entities;

    JpaDatasetsRepository(EntityManager entities) {
        this.entities = entities;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Datasets read() {
        List<Object[]> publications =
                entities.createNativeQuery("SELECT dataset, published_at FROM dataset_publications")
                        .unwrap(NativeQuery.class)
                        .addScalar("dataset", StandardBasicTypes.STRING)
                        .addScalar("published_at", StandardBasicTypes.INSTANT)
                        .getResultList();
        Map<String, Instant> published = new HashMap<>();
        publications.forEach(row -> published.put((String) row[0], (Instant) row[1]));
        List<Object[]> rows =
                entities.createNativeQuery(
                                """
                                SELECT name, mode, longitude, latitude
                                FROM transit_stops
                                ORDER BY name, id
                                """)
                        .getResultList();
        List<StopLocation> stops =
                rows.stream()
                        .map(
                                row ->
                                        new StopLocation(
                                                (String) row[0],
                                                TransitMode.valueOf((String) row[1]),
                                                new Position((Double) row[2], (Double) row[3])))
                        .toList();
        return new Datasets(stops, published.get("TRANSIT_STOPS"), published.get("ADDRESSES"));
    }
}
