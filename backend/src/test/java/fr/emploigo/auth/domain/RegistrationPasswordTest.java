package fr.emploigo.auth.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

class RegistrationPasswordTest {
    /**
     * The cases that the frontend reads too ({@code password-rule.test.ts}): a password is judged
     * the same on both sides.
     */
    static Stream<Arguments> sharedCases() throws IOException {
        var file =
                Path.of(
                        System.getProperty("basedir", "."),
                        "..",
                        "contracts",
                        "password-rule-cases.json");
        var cases = new ObjectMapper().readTree(Files.readString(file)).path("cases");
        assertThat(cases.size()).as("given: cases of the shared file").isPositive();
        return cases.valueStream()
                .map(
                        item ->
                                argumentSet(
                                        item.path("name").asString(),
                                        item.path("password").asString(),
                                        item.path("accepted").asBoolean()));
    }

    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("sharedCases")
    void sharedCaseIsJudgedAsTheFrontendDoes(String password, boolean accepted) {
        assertThat(RegistrationPassword.meetsRule(password))
                .as("password meets the rule")
                .isEqualTo(accepted);
    }

    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("passwordsThatMeetTheRule")
    void passwordThatMeetsTheRuleIsAccepted(String password) {
        assertThat(RegistrationPassword.meetsRule(password)).as("password meets the rule").isTrue();
    }

    static Stream<Arguments> passwordsThatMeetTheRule() {
        return Stream.of(
                argumentSet("mélange de symboles", "V7!rQ2#mL9@x"),
                argumentSet("phrase avec espaces et accents", "Quatre arbres près du canal 4"),
                argumentSet("courant mais conforme", "Password1!"));
    }

    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("passwordsBreakingTheRule")
    void passwordBreakingTheRuleIsRejected(String password) {
        assertThat(RegistrationPassword.meetsRule(password)).as("password meets the rule").isFalse();
    }

    static Stream<Arguments> passwordsBreakingTheRule() {
        return Stream.of(
                argumentSet("absent", (String) null),
                argumentSet("ancienne règle : 12 minuscules", "abcdefghijkl"),
                argumentSet(
                        "ancienne règle : phrase sans chiffre",
                        "une phrase sans chiffre ni symbole"));
    }
}
