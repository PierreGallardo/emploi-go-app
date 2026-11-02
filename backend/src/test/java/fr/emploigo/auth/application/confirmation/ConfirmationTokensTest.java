package fr.emploigo.auth.application.confirmation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import fr.emploigo.auth.domain.AuthenticationSecrets;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.UUID;
import java.util.function.BiFunction;

class ConfirmationTokensTest {
    private static final UUID OPERATION = UUID.fromString("0f8fad5b-d9cb-469f-a165-70867728950e");
    private static final UUID OTHER = UUID.fromString("1f8fad5b-d9cb-469f-a165-70867728950e");

    private static ConfirmationTokens tokens(byte fill) {
        var key = new byte[32];
        Arrays.fill(key, fill);
        return new ConfirmationTokens(new ConfirmationKeySettings(true, key));
    }

    @ParameterizedTest(name = "[{0}]")
    @ValueSource(
            strings = {
                "00000000-0000-0000-0000-000000000000",
                "ffffffff-ffff-ffff-ffff-ffffffffffff",
                "0f8fad5b-d9cb-469f-a165-70867728950e"
            })
    void codeIsSixDigits(String operation) {
        assertThat(tokens((byte) 1).code(UUID.fromString(operation))).matches("[0-9]{6}");
    }

    @Test
    void codeCheckIsNotTheCode() {
        var tokens = tokens((byte) 1);
        String code = tokens.code(OPERATION);

        assertThat(tokens.codeCheck(OPERATION, code)).isNotEqualTo(code).doesNotContain(code);
    }

    @Test
    void codeCheckIsStableForTheSameOperationAndCode() {
        var tokens = tokens((byte) 1);

        assertThat(tokens.codeCheck(OPERATION, "123456"))
                .isEqualTo(tokens.codeCheck(OPERATION, "123456"));
    }

    @Test
    void codeCheckDependsOnTheOperation() {
        var tokens = tokens((byte) 1);

        assertThat(tokens.codeCheck(OPERATION, "123456"))
                .isNotEqualTo(tokens.codeCheck(OTHER, "123456"));
    }

    @Test
    void codeCheckDependsOnTheCode() {
        var tokens = tokens((byte) 1);

        assertThat(tokens.codeCheck(OPERATION, "123456"))
                .isNotEqualTo(tokens.codeCheck(OPERATION, "123457"));
    }

    @Test
    void codeCheckOfTheRightCodeMatchesAndOfAnotherCodeDoesNot() {
        var tokens = tokens((byte) 1);
        String stored = AuthenticationSecrets.hash(tokens.codeCheck(OPERATION, "123456"));

        assertThat(AuthenticationSecrets.matches(tokens.codeCheck(OPERATION, "123456"), stored))
                .as("right code")
                .isTrue();
        assertThat(AuthenticationSecrets.matches(tokens.codeCheck(OPERATION, "654321"), stored))
                .as("other code")
                .isFalse();
    }

    @Test
    void codeCheckIsAnotherDerivationThanTheTokensOfTheOtherFlows() {
        var tokens = tokens((byte) 1);

        assertThat(tokens.codeCheck(OPERATION, "123456"))
                .isNotEqualTo(tokens.recoveryToken(OPERATION))
                .isNotEqualTo(tokens.googleToken(OPERATION));
    }

    private enum Derivation {
        CODE(ConfirmationTokens::code),
        CODE_CHECK((tokens, operation) -> tokens.codeCheck(operation, "123456")),
        RECOVERY_TOKEN(ConfirmationTokens::recoveryToken),
        GOOGLE_TOKEN(ConfirmationTokens::googleToken);

        final BiFunction<ConfirmationTokens, UUID, String> derive;

        Derivation(BiFunction<ConfirmationTokens, UUID, String> derive) {
            this.derive = derive;
        }
    }

    @ParameterizedTest
    @EnumSource(Derivation.class)
    void derivationIsStableForTheSameOperationAndKey(Derivation derivation) {
        assertThat(derivation.derive.apply(tokens((byte) 1), OPERATION))
                .isEqualTo(derivation.derive.apply(tokens((byte) 1), OPERATION));
    }

    @ParameterizedTest
    @EnumSource(Derivation.class)
    void derivationDiffersBetweenOperations(Derivation derivation) {
        var tokens = tokens((byte) 1);

        assertThat(derivation.derive.apply(tokens, OPERATION))
                .isNotEqualTo(derivation.derive.apply(tokens, OTHER));
    }

    @ParameterizedTest
    @EnumSource(Derivation.class)
    void derivationDependsOnTheKeyOfTheServer(Derivation derivation) {
        assertThat(derivation.derive.apply(tokens((byte) 1), OPERATION))
                .isNotEqualTo(derivation.derive.apply(tokens((byte) 2), OPERATION));
    }

    @Test
    void recoveryTokenAndGoogleTokenOfOneOperationDiffer() {
        var tokens = tokens((byte) 1);

        assertThat(tokens.recoveryToken(OPERATION)).isNotEqualTo(tokens.googleToken(OPERATION));
    }

    private static ConfirmationTokens disabledTokens() {
        return new ConfirmationTokens(new ConfirmationKeySettings(false, new byte[0]));
    }

    @ParameterizedTest
    @EnumSource(Derivation.class)
    void derivationOfADisabledChannelFailsWithAClearMessage(Derivation derivation) {
        assertThatThrownBy(() -> derivation.derive.apply(disabledTokens(), OPERATION))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Le canal de courriel est désactivé.");
    }
}
