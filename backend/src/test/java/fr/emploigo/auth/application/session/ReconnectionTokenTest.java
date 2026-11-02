package fr.emploigo.auth.application.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import fr.emploigo.auth.domain.AuthenticationSecrets;
import fr.emploigo.auth.application.session.ReconnectionRepository.Reconnection;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.stream.Stream;

class ReconnectionTokenTest {
    private static final Instant NOW = Instant.parse("2026-10-16T10:00:00Z");
    private static final UUID FAMILY = UUID.fromString("0f8fad5b-d9cb-469f-a165-70867728950e");
    private static final ReconnectionToken CURRENT = new ReconnectionToken(FAMILY, "current");

    private static Reconnection family(
            String secret, String previous, boolean revoked, Instant expiresAt) {
        return new Reconnection(
                FAMILY,
                1L,
                1L,
                AuthenticationSecrets.hash(secret),
                previous == null ? null : AuthenticationSecrets.hash(previous),
                expiresAt,
                NOW,
                revoked);
    }

    @Test
    void aCookieMadeOfAFamilyADotAndASecretIsRead() {
        String secret = AuthenticationSecrets.generate();

        var token = ReconnectionToken.parse(FAMILY + "." + secret);

        assertThat(token).as("token").hasValue(new ReconnectionToken(FAMILY, secret));
    }

    @Test
    void theCurrentSecretOfALiveFamilyIsUsable() {
        assertThat(CURRENT.usable(family("current", "older", false, NOW.plusSeconds(1)), NOW))
                .as("usable")
                .isTrue();
    }

    static Stream<Arguments> familiesThatAreNotUsable() {
        return Stream.of(
                argumentSet("secret faux", family("other", null, false, NOW.plusSeconds(1))),
                argumentSet("famille révoquée", family("current", null, true, NOW.plusSeconds(1))),
                argumentSet("expirée à l'instant", family("current", null, false, NOW)),
                argumentSet(
                        "expirée depuis longtemps",
                        family("current", null, false, NOW.minusSeconds(86_400))),
                argumentSet("inconnue", (Reconnection) null));
    }

    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("familiesThatAreNotUsable")
    void aWrongRevokedExpiredOrUnknownFamilyIsNotUsable(Reconnection row) {
        assertThat(CURRENT.usable(row, NOW)).as("usable").isFalse();
    }

    @Test
    void thePreviousSecretOfALiveFamilyReplaysButIsNotUsable() {
        var token = new ReconnectionToken(FAMILY, "older");
        var row = family("current", "older", false, NOW.plusSeconds(1));

        assertThat(token.replays(row, NOW)).as("replays").isTrue();
        assertThat(token.usable(row, NOW)).as("usable").isFalse();
    }

    static Stream<Arguments> familiesThatTheSecretDoesNotReplay() {
        return Stream.of(
                argumentSet(
                        "famille révoquée", family("current", "older", true, NOW.plusSeconds(1))),
                argumentSet("famille expirée", family("current", "older", false, NOW)),
                argumentSet("famille inconnue", (Reconnection) null),
                argumentSet(
                        "secret qui n'est pas le précédent",
                        family("current", "another", false, NOW.plusSeconds(1))),
                argumentSet(
                        "famille jamais renouvelée, sans secret précédent",
                        family("current", null, false, NOW.plusSeconds(1))));
    }

    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("familiesThatTheSecretDoesNotReplay")
    void aSecretReplaysOnlyThePreviousSecretOfALiveFamily(Reconnection row) {
        assertThat(new ReconnectionToken(FAMILY, "older").replays(row, NOW))
                .as("replays")
                .isFalse();
    }

    @Test
    void theCurrentSecretDoesNotReplay() {
        assertThat(CURRENT.replays(family("current", "older", false, NOW.plusSeconds(1)), NOW))
                .as("replays")
                .isFalse();
    }

    @Test
    void aSecretBelongsToTheFamilyWhenItIsTheCurrentOrThePreviousOneEvenIfTheFamilyIsOver() {
        var row = family("current", "older", true, NOW.minusSeconds(1));

        assertThat(new ReconnectionToken(FAMILY, "current").belongsTo(row)).as("current").isTrue();
        assertThat(new ReconnectionToken(FAMILY, "older").belongsTo(row)).as("previous").isTrue();
    }

    @Test
    void anotherSecretBelongsToNoFamilyWhetherOrNotItWasRenewed() {
        var token = new ReconnectionToken(FAMILY, "another");

        assertThat(token.belongsTo(family("current", "older", false, NOW.plusSeconds(1))))
                .as("renewed family")
                .isFalse();
        assertThat(token.belongsTo(family("current", null, false, NOW.plusSeconds(1))))
                .as("family never renewed")
                .isFalse();
    }

    @Test
    void theStringFormNeverShowsTheSecret() {
        assertThat(new ReconnectionToken(FAMILY, "do-not-print").toString())
                .as("string form")
                .doesNotContain("do-not-print");
    }

    private static final Duration TOLERANCE = Duration.ofSeconds(30);

    private static Reconnection rotatedAt(Instant rotatedAt) {
        return new Reconnection(
                FAMILY,
                1L,
                1L,
                AuthenticationSecrets.hash("current"),
                AuthenticationSecrets.hash("older"),
                NOW.plusSeconds(86_400),
                rotatedAt,
                false);
    }

    static Stream<Arguments> rotationsWithinTheTolerance() {
        return Stream.of(
                argumentSet("aussitôt", NOW), argumentSet("à la limite", NOW.minus(TOLERANCE)));
    }

    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("rotationsWithinTheTolerance")
    void thePreviousSecretReplacedWithinTheToleranceIsAdmitted(Instant rotatedAt) {
        var token = new ReconnectionToken(FAMILY, "older");

        assertThat(token.admitted(rotatedAt(rotatedAt), NOW, TOLERANCE)).as("admitted").isTrue();
    }

    @Test
    void thePreviousSecretReplacedBeforeTheToleranceIsNotAdmitted() {
        var token = new ReconnectionToken(FAMILY, "older");
        var row = rotatedAt(NOW.minus(TOLERANCE).minusMillis(1));

        assertThat(token.admitted(row, NOW, TOLERANCE)).as("admitted").isFalse();
    }

    @Test
    void theCurrentSecretIsAdmittedWhateverTheDateOfItsRotation() {
        var row = rotatedAt(NOW.minus(Duration.ofDays(1)));

        assertThat(CURRENT.admitted(row, NOW, TOLERANCE)).as("admitted").isTrue();
    }

    @Test
    void aSecretOlderThanThePreviousOneIsNotAdmitted() {
        var token = new ReconnectionToken(FAMILY, "oldest");

        assertThat(token.admitted(rotatedAt(NOW), NOW, TOLERANCE)).as("admitted").isFalse();
    }

    @Test
    void theSecretsOfARevokedFamilyAreNotAdmitted() {
        var row =
                new Reconnection(
                        FAMILY,
                        1L,
                        1L,
                        AuthenticationSecrets.hash("current"),
                        AuthenticationSecrets.hash("older"),
                        NOW.plusSeconds(86_400),
                        NOW,
                        true);

        assertThat(new ReconnectionToken(FAMILY, "older").admitted(row, NOW, TOLERANCE))
                .as("admitted")
                .isFalse();
    }
}
