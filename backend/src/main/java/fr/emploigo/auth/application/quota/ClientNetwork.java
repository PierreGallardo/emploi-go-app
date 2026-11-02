package fr.emploigo.auth.application.quota;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The client counted by the quota: a whole IPv6 /60, or an IPv4 address (also when wrapped in IPv6,
 * NAT64 included). The address is read as a literal, never resolved. A /60 is the smallest
 * delegation a fixed-line operator gives to a subscriber (16 networks of 64 bits), so a client that
 * walks its own networks keeps one counter. Mobile subscribers get a /64 each from an operator pool
 * and can share a /60, hence a counter.
 */
public final class ClientNetwork {
    private static final int NETWORK_DIGITS = 15;
    private static final int PREFIX48_DIGITS = 12;
    private static final String IPV6 = "v6:";
    private static final Pattern IPV6_NETWORK =
            Pattern.compile("v6:[0-9a-f]{" + NETWORK_DIGITS + "}");

    /**
     * The well-known NAT64 prefix {@code 64:ff9b::/96}: the last four bytes are an IPv4 address.
     */
    private static final byte[] NAT64 = {
        0x00, 0x64, (byte) 0xff, (byte) 0x9b, 0, 0, 0, 0, 0, 0, 0, 0
    };

    private ClientNetwork() {}

    /** The network of {@code address}, or empty when the text is not an address. */
    public static Optional<String> parse(String address) {
        var literal = address.strip();
        int zone = literal.indexOf('%');
        // A zone names an interface of the host: the address is still the same client, and an
        // unknown name would
        // make the parser refuse it.
        if (zone >= 0) literal = literal.substring(0, zone);
        InetAddress parsed;
        try {
            parsed = InetAddress.ofLiteral(literal);
        } catch (IllegalArgumentException notAnAddress) {
            return Optional.empty();
        }
        return switch (parsed) {
            case Inet4Address ipv4 -> Optional.of(ipv4.getHostAddress());
            case Inet6Address ipv6 -> Optional.of(translated(ipv6.getAddress()));
            default -> Optional.empty();
        };
    }

    public static String of(String address) {
        return parse(address).orElse(address);
    }

    /**
     * The /48 prefix, over whose 60-bit networks an IPv6 client spreads to escape their count; an
     * IPv4 address, or one an IPv6 address wraps, has none.
     */
    public static Optional<String> prefix48(String network) {
        if (!IPV6_NETWORK.matcher(network).matches()) return Optional.empty();
        return Optional.of(network.substring(0, IPV6.length() + PREFIX48_DIGITS));
    }

    private static String translated(byte[] ipv6) {
        if (Arrays.equals(ipv6, 0, NAT64.length, NAT64, 0, NAT64.length))
            return (ipv6[12] & 0xff)
                    + "."
                    + (ipv6[13] & 0xff)
                    + "."
                    + (ipv6[14] & 0xff)
                    + "."
                    + (ipv6[15] & 0xff);
        return IPV6 + HexFormat.of().formatHex(ipv6, 0, 8).substring(0, NETWORK_DIGITS);
    }
}
