package fr.emploigo.auth.application;

import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import org.junit.jupiter.params.provider.Arguments;

import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Texts that are not a token {@code <id>.<secret>}: one source for the format test and the route
 * tests of {@code api}.
 */
public final class MalformedTokens {
    private static final String ID = "0f8fad5b-d9cb-469f-a165-70867728950e";
    private static final String SECRET = "s".repeat(43);

    /** The parts of a token that has the right length, to change one character at a time. */
    private static final String SECRET_OF_42 = "s".repeat(42);

    private record Malformed(String label, String value) {}

    private static final List<Malformed> ALL =
            List.of(
                    new Malformed("absent", null),
                    new Malformed("vide", ""),
                    new Malformed("espaces", " ".repeat(80)),
                    new Malformed("trop court", ID + "." + SECRET_OF_42),
                    new Malformed("trop long", ID + "." + SECRET + "s"),
                    new Malformed("sans point", "a".repeat(80)),
                    new Malformed(
                            "point une place trop tôt", ID.substring(0, 35) + "." + "s".repeat(44)),
                    new Malformed("point une place trop tard", ID + "s." + SECRET_OF_42),
                    new Malformed(
                            "famille qui n'est pas hexadécimale", "z".repeat(36) + "." + SECRET),
                    new Malformed(
                            "famille avec un tiret déplacé",
                            "0000000-0000-0000-0000-0000000000000." + SECRET),
                    new Malformed(
                            "famille avec un composant trop court",
                            "0000000-00000-0000-0000-000000000000." + SECRET),
                    new Malformed(
                            "famille avec un signe",
                            "+0000000-0000-0000-0000-000000000000." + SECRET),
                    new Malformed(
                            "famille en chiffres arabo-indiens",
                            "٠".repeat(8) + "-0000-0000-0000-000000000000." + SECRET),
                    new Malformed(
                            "famille en majuscules", ID.toUpperCase(Locale.ROOT) + "." + SECRET),
                    new Malformed("famille avec un espace", " " + ID.substring(1) + "." + SECRET),
                    new Malformed(
                            "famille terminée par un retour à la ligne",
                            ID.substring(0, 35) + "\n." + SECRET),
                    new Malformed(
                            "famille avec un caractère hors du plan de base",
                            ID.substring(0, 34) + "😀." + SECRET),
                    new Malformed("souligné à la place du point", ID + "_" + SECRET),
                    new Malformed("tiret à la place du point", ID + "-" + SECRET),
                    new Malformed(
                            "secret avec un caractère hors de l'alphabet",
                            ID + "." + SECRET_OF_42 + "!"),
                    new Malformed("secret avec un espace", ID + "." + SECRET_OF_42 + " "),
                    new Malformed("secret avec un point", ID + "." + SECRET_OF_42 + "."),
                    new Malformed(
                            "secret avec une lettre accentuée", ID + "." + SECRET_OF_42 + "é"),
                    new Malformed("secret avec un plus", ID + "." + SECRET_OF_42 + "+"),
                    new Malformed("secret avec une barre oblique", ID + "." + SECRET_OF_42 + "/"),
                    new Malformed("secret avec un remplissage", ID + "." + SECRET_OF_42 + "="),
                    new Malformed(
                            "secret suivi d'un retour à la ligne", ID + "." + SECRET_OF_42 + "\n"),
                    new Malformed("secret avec un caractère nul", ID + "." + SECRET_OF_42 + "\0"));

    private MalformedTokens() {}

    public static Stream<Arguments> arguments() {
        return ALL.stream().map(malformed -> argumentSet(malformed.label(), malformed.value()));
    }

    /**
     * Those that have exactly the 80 characters of a token and are not blank, as the field of the
     * Google link requires.
     */
    public static Stream<Arguments> asLinksOfTheLengthOfAToken() {
        return ALL.stream()
                .filter(
                        malformed ->
                                malformed.value() != null
                                        && malformed.value().length() == 80
                                        && !malformed.value().isBlank())
                .map(malformed -> argumentSet(malformed.label(), malformed.value()));
    }
}
