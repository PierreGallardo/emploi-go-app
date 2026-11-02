package fr.emploigo.auth.application.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import fr.emploigo.auth.domain.AuthenticationSecrets;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.UUID;
import java.util.stream.Stream;

class AuthenticationTokenTest {
    private static final UUID ID = UUID.fromString("0f8fad5b-d9cb-469f-a165-70867728950e");

    @Test
    void aTextMadeOfAnIdADotAndASecretIsRead() {
        String secret = AuthenticationSecrets.generate();

        var token = AuthenticationToken.parse(ID + "." + secret);

        assertThat(token).as("token").hasValue(new AuthenticationToken(ID, secret));
    }

    @Test
    void theSecretsTheApplicationIssuesAreReadWhateverTheirCharacters() {
        var texts =
                Stream.generate(() -> ID + "." + AuthenticationSecrets.generate())
                        .limit(500)
                        .toList();

        assertThat(texts)
                .as("texts")
                .allSatisfy(
                        text ->
                                assertThat(AuthenticationToken.parse(text))
                                        .as("token")
                                        .isPresent());
    }

    static Stream<Arguments> secretsOfTheAlphabet() {
        return Stream.of(
                argumentSet("minuscules", "a".repeat(43)),
                argumentSet("majuscules", "Z".repeat(43)),
                argumentSet("chiffres", "7".repeat(43)),
                argumentSet("tiret", "-".repeat(43)),
                argumentSet("souligné", "_".repeat(43)));
    }

    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("secretsOfTheAlphabet")
    void everyCharacterOfTheAlphabetIsReadInASecret(String secret) {
        assertThat(AuthenticationToken.parse(ID + "." + secret))
                .as("token")
                .hasValue(new AuthenticationToken(ID, secret));
    }

    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("fr.emploigo.auth.application.MalformedTokens#arguments")
    void anyOtherTextIsNotAToken(String text) {
        assertThat(AuthenticationToken.parse(text)).as("token").isEmpty();
    }

    @Test
    void theStringFormNeverShowsTheSecret() {
        assertThat(new AuthenticationToken(ID, "do-not-print").toString())
                .as("string form")
                .doesNotContain("do-not-print");
    }
}
