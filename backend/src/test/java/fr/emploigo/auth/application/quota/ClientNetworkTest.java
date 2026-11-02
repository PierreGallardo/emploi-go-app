package fr.emploigo.auth.application.quota;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

class ClientNetworkTest {
    static Stream<Arguments> sameNetwork() {
        return Stream.of(
                argumentSet(
                        "deux adresses du même /64",
                        "2001:db8:1:2:3:4:5:6",
                        "2001:db8:1:2:ffff:eeee:dddd:cccc"),
                argumentSet(
                        "forme compressée et développée",
                        "2001:db8::1",
                        "2001:0db8:0:0:0:0:0:0001"),
                argumentSet("majuscules et minuscules", "2001:DB8:A::1", "2001:db8:a:0:0:0:0:2"),
                argumentSet("zone d'interface", "fe80::1%eth0", "fe80::2%en0"),
                argumentSet("zone et sans zone", "fe80::1%eth0", "fe80::abcd"),
                argumentSet("espaces autour", " 2001:db8:1:2::1 ", "2001:db8:1:2::9"),
                argumentSet("les seize /64 d'un /60", "2001:db8:1:20::1", "2001:db8:1:2f:a::1"),
                argumentSet("bouclage et son /60", "::1", "0:0:0:0:0:0:0:ffff"));
    }

    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("sameNetwork")
    void addressesOfTheSameIpv6PrefixShareOneNetwork(String first, String second) {
        assertThat(ClientNetwork.of(first)).as("network").isEqualTo(ClientNetwork.of(second));
    }

    static Stream<Arguments> otherNetwork() {
        return Stream.of(
                argumentSet("/60 voisin", "2001:db8:1:10::1", "2001:db8:1:20::1"),
                argumentSet("dernier bit du /60 différent", "2001:db8:1:f::1", "2001:db8:1:10::1"),
                argumentSet("IPv4 différente", "203.0.113.7", "203.0.113.8"),
                argumentSet("IPv4 et IPv6", "203.0.113.7", "2001:db8::1"));
    }

    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("otherNetwork")
    void addressesOfAnotherPrefixAreAnotherNetwork(String first, String second) {
        assertThat(ClientNetwork.of(first)).as("network").isNotEqualTo(ClientNetwork.of(second));
    }

    static Stream<Arguments> sameAsIpv4() {
        return Stream.of(
                argumentSet("IPv4 simple", "203.0.113.7"),
                argumentSet("mappée en décimal", "::ffff:203.0.113.7"),
                argumentSet("mappée en hexadécimal", "::ffff:cb00:7107"),
                argumentSet("mappée développée", "0:0:0:0:0:ffff:203.0.113.7"),
                argumentSet("mappée avec zone", "::ffff:203.0.113.7%eth0"),
                argumentSet("traduite par NAT64 en hexadécimal", "64:ff9b::cb00:7107"),
                argumentSet("traduite par NAT64 en décimal", "64:ff9b::203.0.113.7"));
    }

    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("sameAsIpv4")
    void ipv4AddressKeepsItsFullFormWhateverItsNotation(String address) {
        assertThat(ClientNetwork.of(address)).as("network").isEqualTo("203.0.113.7");
    }

    static Stream<Arguments> unreadable() {
        return Stream.of(
                argumentSet("nom d'hôte", "unresolvable.invalid"),
                argumentSet("texte quelconque", "not-an-address"),
                argumentSet("vide", ""),
                argumentSet("IPv4 à cinq nombres", "203.0.113.7.9"));
    }

    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("unreadable")
    void unreadableAddressIsKeptAsItIs(String address) {
        assertThat(ClientNetwork.of(address)).as("network").isEqualTo(address);
    }

    @Test
    void allSixtyFiveThousandNetworksOfAPrefix48FallInFourThousandNinetySixClients() {
        var networks =
                IntStream.range(0, 65_536)
                        .mapToObj(
                                index ->
                                        ClientNetwork.of(
                                                "2001:db8:1:" + Integer.toHexString(index) + "::1"))
                        .collect(Collectors.toSet());

        assertThat(networks).hasSize(4096).allMatch(network -> network.matches("v6:[0-9a-f]{15}"));
    }

    @Test
    void networkOfAnIpv6ClientIsItsFirstSixtyBits() {
        assertThat(ClientNetwork.of("2001:db8:1:2f:3:4:5:6")).isEqualTo("v6:20010db80001002");
    }

    static Stream<Arguments> samePrefix48() {
        return Stream.of(
                argumentSet("deux /64 voisins du même /48", "2001:db8:1:2::1", "2001:db8:1:3::1"),
                argumentSet(
                        "deux /64 aux extrémités du /48", "2001:db8:1:0::1", "2001:db8:1:ffff::1"),
                argumentSet(
                        "majuscules et forme développée",
                        "2001:DB8:1:2::1",
                        "2001:0db8:0001:0003:0:0:0:9"));
    }

    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("samePrefix48")
    void networksOfOneFortyEightShareTheirPrefix(String first, String second) {
        assertThat(ClientNetwork.prefix48(ClientNetwork.of(first)))
                .as("prefix")
                .isPresent()
                .isEqualTo(ClientNetwork.prefix48(ClientNetwork.of(second)));
    }

    static Stream<Arguments> otherPrefix48() {
        return Stream.of(
                argumentSet("/48 voisin", "2001:db8:1:2::1", "2001:db8:2:2::1"),
                argumentSet("dernier bit du /48 différent", "2001:db8:1:2::1", "2001:db8:0:2::1"),
                argumentSet("autre préfixe", "2001:db8:1:2::1", "2001:db9:1:2::1"));
    }

    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("otherPrefix48")
    void networksOfAnotherFortyEightHaveAnotherPrefix(String first, String second) {
        var prefix = ClientNetwork.prefix48(ClientNetwork.of(first));

        assertThat(prefix).as("prefix").isPresent();
        assertThat(ClientNetwork.prefix48(ClientNetwork.of(second)))
                .as("other prefix")
                .isPresent()
                .isNotEqualTo(prefix);
    }

    @Test
    void prefixIsTheFirstFortyEightBitsOfTheNetwork() {
        assertThat(ClientNetwork.prefix48(ClientNetwork.of("2001:db8:1:2:3:4:5:6")))
                .as("prefix")
                .contains("v6:20010db80001");
    }

    static Stream<Arguments> withoutPrefix() {
        return Stream.concat(
                sameAsIpv4(),
                Stream.of(
                        argumentSet("IPv4 abrégée", "127.1"),
                        argumentSet("IPv4 en un nombre", "2130706433"),
                        argumentSet("IPv4 en hexadécimal", "0x7f.1")));
    }

    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("withoutPrefix")
    void clientOfIpv4HasNoPrefixWhateverTheNotationOfItsAddress(String address) {
        assertThat(ClientNetwork.prefix48(ClientNetwork.of(address))).as("prefix").isEmpty();
    }

    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("unreadable")
    void unreadableAddressHasNoPrefix(String address) {
        assertThat(ClientNetwork.prefix48(ClientNetwork.of(address))).as("prefix").isEmpty();
    }
}
