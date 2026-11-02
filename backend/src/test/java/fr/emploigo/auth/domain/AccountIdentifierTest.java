package fr.emploigo.auth.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Runs only in the Surefire execution {@code locale-tr} (Turkish default locale, see {@code
 * pom.xml}): the keys must not depend on the server locale.
 */
@EnabledIfSystemProperty(named = "emploi-go.locale-tr", matches = "true")
class AccountIdentifierTest {
    @Test
    void jvmDefaultLocaleIsTurkish() {
        assertThat(Locale.getDefault().toLanguageTag())
                .as("default locale of the JVM")
                .isEqualTo("tr-TR");
    }

    /**
     * The cases that the frontend reads too ({@code schemas.test.ts}), accepted or refused: a
     * username is judged the same on both sides.
     */
    private static Stream<JsonNode> sharedCases(boolean accepted) throws IOException {
        var file =
                Path.of(
                        System.getProperty("basedir", "."),
                        "..",
                        "contracts",
                        "username-rule-cases.json");
        var cases = new ObjectMapper().readTree(Files.readString(file)).path("cases");
        assertThat(cases.size()).as("given: cases of the shared file").isPositive();
        return cases.valueStream().filter(item -> item.path("accepted").asBoolean() == accepted);
    }

    static Stream<Arguments> acceptedUsernames() throws IOException {
        return sharedCases(true)
                .map(
                        item ->
                                argumentSet(
                                        item.path("name").asString(),
                                        item.path("input").asString(),
                                        item.path("stored").asString()));
    }

    static Stream<Arguments> refusedUsernames() throws IOException {
        return sharedCases(false)
                .map(
                        item ->
                                argumentSet(
                                        item.path("name").asString(),
                                        item.path("input").asString()));
    }

    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("acceptedUsernames")
    void usernameWithinTheRuleIsStoredInLowerCase(String input, String stored) {
        assertThat(AccountIdentifier.username(input)).as("stored username").isEqualTo(stored);
    }

    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("refusedUsernames")
    void usernameOutsideTheRuleIsRefused(String input) {
        assertThatIllegalArgumentException().isThrownBy(() -> AccountIdentifier.username(input));
    }

    @Test
    void emailKeyStripsSpacesAndLowersCase() {
        assertThat(AccountIdentifier.emailKey(" Jeune@EXAMPLE.invalid "))
                .isEqualTo("jeune@example.invalid");
    }

    @Test
    void emailKeyLowersTheLatinCapitalIAsAnOrdinaryI() {
        assertThat(AccountIdentifier.emailKey("ILIAS@EXAMPLE.INVALID"))
                .isEqualTo("ilias@example.invalid");
    }
}
