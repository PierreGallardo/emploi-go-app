package fr.emploigo.auth.infrastructure.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import fr.emploigo.auth.application.AuthenticationIntegrationTest;
import fr.emploigo.auth.application.account.AccountRepository;
import fr.emploigo.auth.application.session.ReconnectionRepository;
import fr.emploigo.auth.domain.Account;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.stream.Stream;

class JpaReconnectionRepositoryIT extends AuthenticationIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-18T12:00:00Z");
    private static final Instant LIVE_UNTIL = NOW.plus(Duration.ofDays(1));

    /** Index of the account that owns the family, and of the other one, in the checks below. */
    private static final int OWNER = 0;
    private static final int OTHER = 1;

    private static final BiConsumer<ReconnectionRepository, UUID> NO_CHANGE =
            (repository, family) -> {};
    private static final BiConsumer<ReconnectionRepository, UUID> REVOKE =
            (repository, family) -> repository.revoke(family);

    @Autowired AccountRepository accounts;
    @Autowired ReconnectionRepository reconnections;

    private Account account(String name) {
        return accounts.save(new Account(name, name + "@example.invalid", "hash", true, true));
    }

    private UUID family(Account account, long version, Instant expiresAt) {
        var family = UUID.randomUUID();
        reconnections.insert(family, account.id(), version, "h".repeat(64), expiresAt, NOW);
        return family;
    }

    static Stream<Arguments> familiesThatAreNotActive() {
        return Stream.of(
                argumentSet("autre version", NO_CHANGE, 1L, OWNER, LIVE_UNTIL),
                argumentSet("autre compte", NO_CHANGE, 0L, OTHER, LIVE_UNTIL),
                argumentSet("révoquée", REVOKE, 0L, OWNER, LIVE_UNTIL),
                argumentSet("expirée à l'instant", NO_CHANGE, 0L, OWNER, NOW));
    }

    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("familiesThatAreNotActive")
    void aFamilyIsNotActiveForAnotherVersionAccountOrAfterARevocation(
            BiConsumer<ReconnectionRepository, UUID> change,
            long checkedVersion,
            int checkedAccount,
            Instant expiresAt) {
        var account = account("jeune");
        var other = account("autre");
        var family = family(account, 0, expiresAt);
        change.accept(reconnections, family);
        var checked = List.of(account, other).get(checkedAccount);

        assertThat(reconnections.active(family, checked.id(), checkedVersion, NOW))
                .as("active")
                .isFalse();
    }

    @Test
    void aLiveFamilyOfTheAccountAtItsVersionIsActive() {
        var account = account("jeune");
        var family = family(account, 0, LIVE_UNTIL);

        assertThat(reconnections.active(family, account.id(), 0, NOW)).as("active").isTrue();
    }

    @Test
    void anUnknownFamilyIsNotActive() {
        var account = account("jeune");

        assertThat(reconnections.active(UUID.randomUUID(), account.id(), 0, NOW))
                .as("active")
                .isFalse();
    }
}
