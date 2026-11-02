package fr.emploigo.architecture;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Rules on the text of the sources, for what the bytecode does not show: a SQL text is a constant,
 * which ArchUnit cannot read.
 */
final class SourceRules {
    private SourceRules() {}

    /** The Java files under {@code root} that contain {@code literal}, as paths below it. */
    static List<String> filesContaining(Path root, String literal) {
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(file -> file.toString().endsWith(".java"))
                    .filter(file -> contains(file, literal))
                    .map(file -> root.relativize(file).toString().replace('\\', '/'))
                    .sorted()
                    .toList();
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    /** The number of Java files under {@code root}: a rule over none would pass for nothing. */
    static long javaFiles(Path root) {
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(file -> file.toString().endsWith(".java")).count();
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private static boolean contains(Path file, String literal) {
        try {
            return Files.readString(file)
                    .toLowerCase(Locale.ROOT)
                    .contains(literal.toLowerCase(Locale.ROOT));
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }
}
