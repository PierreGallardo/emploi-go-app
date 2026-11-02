package fr.emploigo.auth.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

class GoogleIdentityTest {
    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("authoritativeEmails")
    void verifiedGmailOrValidatedWorkspaceEmailIsAuthoritative(
            String email, boolean verified, String domain) {
        var identity = GoogleIdentity.verified("subject", email, verified, domain);

        assertThat(identity.authoritativeEmail()).as("authoritative email").isTrue();
    }

    static Stream<Arguments> authoritativeEmails() {
        return Stream.of(
                argumentSet("Gmail vérifié", "fixture@gmail.com", true, null),
                argumentSet(
                        "domaine Workspace validé",
                        "fixture@workspace.invalid",
                        true,
                        "workspace.invalid"));
    }

    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("nonAuthoritativeEmails")
    void otherEmailsAreNotAuthoritative(String email, boolean verified, String domain) {
        var identity = GoogleIdentity.verified("subject", email, verified, domain);

        assertThat(identity.authoritativeEmail()).as("authoritative email").isFalse();
    }

    static Stream<Arguments> nonAuthoritativeEmails() {
        return Stream.of(
                argumentSet(
                        "domaine Workspace sans domaine validé",
                        "fixture@workspace.invalid",
                        true,
                        null),
                argumentSet(
                        "domaine validé différent",
                        "fixture@external.invalid",
                        true,
                        "other.invalid"),
                argumentSet("Gmail non vérifié", "fixture@gmail.com", false, null),
                argumentSet(
                        "domaine validé invalide", "fixture@external.invalid", true, "bad domain"));
    }

    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("unsupportedClaims")
    void unsupportedClaimsAreRejected(String subject, String email) {
        assertThatThrownBy(() -> GoogleIdentity.verified(subject, email, true, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    static Stream<Arguments> unsupportedClaims() {
        return Stream.of(
                argumentSet("sujet vide", "", "fixture@gmail.com"),
                argumentSet("email absent", "subject", null),
                argumentSet("email invalide", "subject", "invalid"));
    }

    @Test
    void identityIsRedactedInItsStringForm() {
        var identity = GoogleIdentity.verified("subject", "fixture@gmail.com", true, null);

        assertThat(identity.toString()).isEqualTo("GoogleIdentity[redacted]");
    }
}
