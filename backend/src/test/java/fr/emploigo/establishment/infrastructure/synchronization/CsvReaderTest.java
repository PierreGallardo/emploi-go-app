package fr.emploigo.establishment.infrastructure.synchronization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIOException;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

class CsvReaderTest {
    @Test
    void quotedFieldKeepsTheDelimiterAndADoubledQuote() throws IOException {
        List<String> names = names("stop_id,stop_name\n4-1,\"Gare, \"\"Porte\"\" Neuve\"\n");

        assertThat(names).containsExactly("Gare, \"Porte\" Neuve");
    }

    @Test
    void byteOrderMarkIsNoPartOfTheFirstColumn() throws IOException {
        List<String> names = new ArrayList<>();
        CsvReader.read(
                new StringReader("\uFEFFstop_name\nDarcy\n"),
                ',',
                UnpackedLimits.OPEN_DATA,
                row -> names.add(row.get("stop_name")));

        assertThat(names).containsExactly("Darcy");
    }

    @Test
    void absentColumnReadsAsAnEmptyField() throws IOException {
        List<String> names = names("stop_id\n4-1\n");

        assertThat(names).containsExactly("");
    }

    @Test
    void lineEndedByACarriageReturnAndALineFeedLosesBoth() throws IOException {
        List<String> names = names("stop_name\r\nDarcy\r\nGare\r\n");

        assertThat(names).containsExactly("Darcy", "Gare");
    }

    @Test
    void lineLongerThanTheLimitFailsTheReading() {
        String table = "stop_name\n" + "A".repeat(11) + "\n";

        assertThatIOException()
                .isThrownBy(
                        () ->
                                CsvReader.read(
                                        new StringReader(table),
                                        ',',
                                        new UnpackedLimits(1024, 10, 100),
                                        row -> {}));
    }

    @Test
    void tableLongerThanTheLimitFailsTheReading() {
        String table = "stop_name\nDarcy\nGare\nMonge\n";

        assertThatIOException()
                .isThrownBy(
                        () ->
                                CsvReader.read(
                                        new StringReader(table),
                                        ',',
                                        new UnpackedLimits(1024, 100, 2),
                                        row -> {}));
    }

    private static List<String> names(String table) throws IOException {
        List<String> names = new ArrayList<>();
        CsvReader.read(
                new StringReader(table),
                ',',
                UnpackedLimits.OPEN_DATA,
                row -> names.add(row.get("stop_name")));
        return names;
    }
}
