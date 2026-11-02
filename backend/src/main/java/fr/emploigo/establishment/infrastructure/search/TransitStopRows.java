package fr.emploigo.establishment.infrastructure.search;

import fr.emploigo.establishment.domain.Position;
import fr.emploigo.establishment.domain.TransitLine;
import fr.emploigo.establishment.domain.TransitMode;
import fr.emploigo.establishment.domain.TransitStop;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The stops of rows {@code id, name, longitude, latitude, line, mode}, one per line. */
final class TransitStopRows {
    private TransitStopRows() {}

    static List<TransitStop> stops(List<?> results) {
        Map<Integer, Object[]> stops = new LinkedHashMap<>();
        Map<Integer, List<TransitLine>> lines = new LinkedHashMap<>();
        for (Object result : results) {
            Object[] row = (Object[]) result;
            Integer id = (Integer) row[0];
            stops.putIfAbsent(id, row);
            lines.computeIfAbsent(id, key -> new ArrayList<>())
                    .add(new TransitLine((String) row[4], TransitMode.valueOf((String) row[5])));
        }
        return stops.entrySet().stream()
                .map(
                        stop ->
                                new TransitStop(
                                        (String) stop.getValue()[1],
                                        new Position(
                                                (Double) stop.getValue()[2],
                                                (Double) stop.getValue()[3]),
                                        lines.get(stop.getKey())))
                .toList();
    }
}
