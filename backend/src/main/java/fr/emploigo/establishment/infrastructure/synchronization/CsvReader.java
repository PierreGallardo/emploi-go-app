package fr.emploigo.establishment.infrastructure.synchronization;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Reads a CSV table whose first line names the columns. A quoted field may hold the delimiter or a
 * doubled quote, not a line break: neither the GTFS of Divia nor the Base Adresse Nationale has
 * one. A line or a table longer than the limits fails the reading.
 */
final class CsvReader {
    /** A line of the table; an absent column reads as an empty field. */
    static final class Row {
        private final Map<String, Integer> columns;
        private final List<String> fields;

        private Row(Map<String, Integer> columns, List<String> fields) {
            this.columns = columns;
            this.fields = fields;
        }

        String get(String column) {
            Integer index = columns.get(column);
            return index == null || index >= fields.size() ? "" : fields.get(index).trim();
        }
    }

    private CsvReader() {}

    static void read(Reader text, char delimiter, UnpackedLimits limits, Consumer<Row> row)
            throws IOException {
        var lines = new BufferedReader(text);
        String header = line(lines, limits);
        if (header == null) return;
        Map<String, Integer> columns = new HashMap<>();
        List<String> names = split(header.replace("\uFEFF", ""), delimiter);
        for (int index = 0; index < names.size(); index++)
            columns.put(names.get(index).trim(), index);
        int records = 0;
        for (String line = line(lines, limits); line != null; line = line(lines, limits)) {
            if (line.isEmpty()) continue;
            if (++records > limits.maxRecords())
                throw new IOException("plus de " + limits.maxRecords() + " lignes");
            row.accept(new Row(columns, split(line, delimiter)));
        }
    }

    /** The next line without its end, null at the end of the text. */
    private static String line(BufferedReader text, UnpackedLimits limits) throws IOException {
        StringBuilder line = new StringBuilder();
        for (int next = text.read(); next != -1; next = text.read()) {
            if (next == '\n') return stripCarriageReturn(line);
            if (line.length() == limits.maxLineLength())
                throw new IOException("ligne de plus de " + limits.maxLineLength() + " caractères");
            line.append((char) next);
        }
        return line.isEmpty() ? null : stripCarriageReturn(line);
    }

    private static String stripCarriageReturn(StringBuilder line) {
        int end = line.length();
        return end > 0 && line.charAt(end - 1) == '\r'
                ? line.substring(0, end - 1)
                : line.toString();
    }

    private static List<String> split(String line, char delimiter) {
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int index = 0; index < line.length(); index++) {
            char current = line.charAt(index);
            if (quoted
                    && current == '"'
                    && index + 1 < line.length()
                    && line.charAt(index + 1) == '"') {
                field.append('"');
                index++;
            } else if (current == '"') {
                quoted = !quoted;
            } else if (current == delimiter && !quoted) {
                fields.add(field.toString());
                field.setLength(0);
            } else {
                field.append(current);
            }
        }
        fields.add(field.toString());
        return fields;
    }
}
