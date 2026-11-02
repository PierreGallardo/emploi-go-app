package fr.emploigo.auth.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class RegistrationCodeTest {
    @ParameterizedTest(name = "[{0}]")
    @CsvSource(
            quoteCharacter = '\'',
            value = {
                "'123456', 123456",
                "'012345', 012345",
                "'000000', 000000",
                "' 123456 ', 123456",
                "'123 456', 123456",
                "'123-456', 123456",
                "'12 34-56', 123456",
                "'1 2 3 4 5 6', 123456"
            })
    void sixDigitsTypedWithSpacesOrHyphensAreNormalized(String typed, String expected) {
        assertThat(RegistrationCode.normalize(typed)).contains(expected);
    }

    @Test
    void tabsAndLineBreaksAreRemovedLikeSpaces() {
        assertThat(RegistrationCode.normalize("123\t456\n")).contains("123456");
    }

    @ParameterizedTest(name = "[{0}]")
    @ValueSource(
            strings = {
                "",
                "   ",
                "-",
                "12345",
                "1234567",
                "12 345",
                "123 4567",
                "abcdef",
                "12345a",
                "a12345",
                "12.456",
                "12_456",
                "+23456",
                "١٢٣٤٥٦",
                "１２３４５６"
            })
    void textThatIsNotSixDigitsIsRefused(String typed) {
        assertThat(RegistrationCode.normalize(typed)).isEmpty();
    }

    @Test
    void absentCodeIsRefused() {
        assertThat(RegistrationCode.normalize(null)).isEmpty();
    }

    @Test
    void digestOfZerosGivesSixZeros() {
        assertThat(RegistrationCode.fromDigest(new byte[32])).isEqualTo("000000");
    }

    @Test
    void digestKeepsTheLeadingZerosOfTheCode() {
        // 1234 as the first 8 bytes, big-endian: the code is 001234.
        var digest = new byte[32];
        digest[6] = 0x04;
        digest[7] = (byte) 0xD2;

        assertThat(RegistrationCode.fromDigest(digest)).isEqualTo("001234");
    }

    @Test
    void digestIsReducedModuloOneMillion() {
        var digest = new byte[32];
        digest[4] = 0x3B;
        digest[5] = (byte) 0x9A;
        digest[6] = (byte) 0xCA;
        digest[7] = 0x07;

        // 1_000_000_007 mod 1_000_000
        assertThat(RegistrationCode.fromDigest(digest)).isEqualTo("000007");
    }

    @ParameterizedTest(name = "[{0}]")
    @ValueSource(ints = {0x00, 0x01, 0x7F, 0x80, 0xFE, 0xFF})
    void digestFilledWithOneByteStaysSixDigits(int fill) {
        var digest = new byte[32];
        java.util.Arrays.fill(digest, (byte) fill);

        assertThat(RegistrationCode.fromDigest(digest)).matches("[0-9]{6}");
    }

    @Test
    void onlyTheFirstEightBytesOfTheDigestCount() {
        var first = new byte[32];
        var second = new byte[32];
        first[7] = 5;
        second[7] = 5;
        second[8] = 99;
        second[31] = 42;

        assertThat(RegistrationCode.fromDigest(second))
                .isEqualTo(RegistrationCode.fromDigest(first));
    }

    @Test
    void codeFromADigestIsAcceptedAsTyped() {
        var digest = new byte[32];
        digest[7] = 42;

        String code = RegistrationCode.fromDigest(digest);

        assertThat(RegistrationCode.normalize(code)).contains(code);
    }
}
