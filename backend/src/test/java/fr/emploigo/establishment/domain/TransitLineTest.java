package fr.emploigo.establishment.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Stream;

class TransitLineTest {
    @Test
    void linesAreInTheOrderOfTheNetwork() {
        List<String> order =
                Stream.of("T2", "L10", "12", "CO", "L3", "9", "T1", "CITY", "100")
                        .map(name -> new TransitLine(name, TransitMode.BUS))
                        .sorted(TransitLine.ORDER)
                        .map(TransitLine::name)
                        .toList();

        assertThat(order).containsExactly("9", "12", "100", "CITY", "CO", "L3", "L10", "T1", "T2");
    }
}
