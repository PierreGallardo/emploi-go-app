package fr.emploigo.support;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

class HostCookiesTest {
    static Stream<Arguments> compliantCookies() {
        return Stream.of(
                argumentSet(
                        "cookie posé",
                        "__Host-EMPLOIGO_SESSION=abc; Path=/; Secure; HttpOnly; SameSite=Lax"),
                argumentSet(
                        "cookie avec durée",
                        "__Host-EMPLOIGO_RECONNECTION=abc; Max-Age=2592000; Expires=Wed, 04 Nov 2026 10:00:00 GMT; Path=/; Secure; HttpOnly; SameSite=Lax"),
                argumentSet(
                        "cookie effacé",
                        "__Host-EMPLOIGO_SESSION=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:10 GMT; Path=/; Secure; HttpOnly; SameSite=Lax"));
    }

    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("compliantCookies")
    void cookieOfProductionIsAccepted(String header) {
        assertThatCode(() -> HostCookies.assertHostOnly(header))
                .as("check")
                .doesNotThrowAnyException();
    }

    static Stream<Arguments> driftingCookies() {
        return Stream.of(
                argumentSet(
                        "sans préfixe",
                        "EMPLOIGO_CSRF=abc; Path=/; Secure; HttpOnly; SameSite=Lax",
                        "name of the cookie"),
                argumentSet(
                        "sans Secure",
                        "__Host-EMPLOIGO_CSRF=abc; Path=/; HttpOnly; SameSite=Lax",
                        "Secure attribute"),
                argumentSet(
                        "chemin plus étroit",
                        "__Host-EMPLOIGO_RECONNECTION=abc; Path=/api/auth; Secure; HttpOnly; SameSite=Lax",
                        "Path attribute"),
                argumentSet(
                        "sans chemin",
                        "__Host-EMPLOIGO_CSRF=abc; Secure; HttpOnly; SameSite=Lax",
                        "Path attribute"),
                argumentSet(
                        "avec Domain",
                        "__Host-EMPLOIGO_CSRF=abc; Domain=emploigo.fr; Path=/; Secure; HttpOnly; SameSite=Lax",
                        "Domain attribute"),
                argumentSet(
                        "effacement sans préfixe",
                        "EMPLOIGO_RECONNECTION=; Max-Age=0; Path=/; Secure; HttpOnly; SameSite=Lax",
                        "name of the cookie"));
    }

    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("driftingCookies")
    void cookieThatDriftsFromTheRuleIsRefused(String header, String refusedCriterion) {
        assertThatThrownBy(() -> HostCookies.assertHostOnly(header))
                .as("check")
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining(refusedCriterion);
    }
}
