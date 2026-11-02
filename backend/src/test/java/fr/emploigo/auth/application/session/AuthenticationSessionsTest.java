package fr.emploigo.auth.application.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import fr.emploigo.auth.application.account.FakeAccountRepository;
import fr.emploigo.auth.application.failure.AuthenticationFailedException;
import fr.emploigo.auth.domain.Account;
import fr.emploigo.auth.application.session.ReconnectionRepository.Reconnection;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

class AuthenticationSessionsTest {
    private static final Instant NOW = Instant.EPOCH.plus(Duration.ofDays(1));

    private final FakeAccountRepository accounts = new FakeAccountRepository();
    private final FakeReconnectionRepository reconnections = new FakeReconnectionRepository();
    private final UUID family = UUID.randomUUID();

    private AuthenticationSessions sessions() {
        return new AuthenticationSessions(
                accounts, reconnections, null, Clock.fixed(NOW, ZoneOffset.UTC), null);
    }

    /** The family of the account 7 at version 2: live, or revoked. */
    private void family(boolean live) {
        reconnections.store(
                new Reconnection(
                        family,
                        7L,
                        2,
                        "h".repeat(64),
                        null,
                        NOW.plus(Duration.ofDays(1)),
                        NOW,
                        !live));
    }

    private Account account(boolean mayAuthenticate, long version) {
        return new Account(
                7L,
                null,
                "jeune",
                "jeune@example.invalid",
                "hash",
                true,
                mayAuthenticate,
                "JEUNE",
                version);
    }

    @Test
    void verifyOfALiveSessionReturnsTheAccount() {
        accounts.store(account(true, 2));
        family(true);

        assertThat(sessions().verify(7L, 2, family).username()).as("username").isEqualTo("jeune");
    }

    static Stream<Arguments> sessionsThatNoLongerHold() {
        return Stream.of(
                argumentSet("compte inconnu", Optional.empty(), true),
                argumentSet("compte qui ne peut plus se connecter", Optional.of(false), true),
                argumentSet("reconnexion révoquée", Optional.of(true), false));
    }

    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("sessionsThatNoLongerHold")
    void verifyOfASessionThatNoLongerHoldsIsUnauthenticatedNotInvalidCredentials(
            Optional<Boolean> mayAuthenticate, boolean familyActive) {
        var stored = mayAuthenticate.map(value -> account(value, 2));
        stored.ifPresent(accounts::store);
        family(familyActive);

        assertThatThrownBy(() -> sessions().verify(7L, 2, family))
                .as("verify")
                .isInstanceOfSatisfying(
                        AuthenticationFailedException.class, this::isUnauthenticated);
    }

    @Test
    void verifyOfAChangedCredentialVersionIsUnauthenticated() {
        var stored = account(true, 3);
        accounts.store(stored);

        assertThatThrownBy(() -> sessions().verify(7L, 2, family))
                .as("verify")
                .isInstanceOfSatisfying(
                        AuthenticationFailedException.class, this::isUnauthenticated);
    }

    private void isUnauthenticated(AuthenticationFailedException failure) {
        assertThat(failure.reason())
                .as("reason")
                .isEqualTo(AuthenticationFailedException.Reason.UNAUTHENTICATED);
    }
}
