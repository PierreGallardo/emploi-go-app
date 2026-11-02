package fr.emploigo.auth.application.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import fr.emploigo.auth.application.failure.AuthenticationFailedException;
import fr.emploigo.auth.application.session.ReconnectionRepository.Reconnection;
import fr.emploigo.auth.domain.AuthenticationSecrets;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.stream.Stream;

class ResumeSessionUseCaseTest {
    private static final String METER = "auth.reconnection.reuse.revoked";
    private static final Duration TOLERANCE = Duration.ofSeconds(30);
    private static final Instant NOW = Instant.EPOCH.plus(Duration.ofDays(1));

    private final FakeReconnectionRepository reconnections = new FakeReconnectionRepository();
    private final ReconnectionTransactions transactions = mock(ReconnectionTransactions.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final UUID family = UUID.randomUUID();
    private final String current = AuthenticationSecrets.generate();
    private final String older = AuthenticationSecrets.generate();

    private ResumeSessionUseCase useCase(Duration tolerance, SimpleMeterRegistry meters) {
        return new ResumeSessionUseCase(
                null,
                null,
                null,
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
                meters,
                new ReconnectionSettings(tolerance));
    }

    private ResumeSessionUseCase useCase() {
        return new ResumeSessionUseCase(
                reconnections,
                transactions,
                null,
                Clock.fixed(NOW, ZoneOffset.UTC),
                meters,
                new ReconnectionSettings(TOLERANCE));
    }

    /** A live family whose previous secret is {@code older}, rotated before the tolerance. */
    private String familyReplayedAfterTheTolerance() {
        reconnections.store(
                new Reconnection(
                        family,
                        1,
                        1,
                        AuthenticationSecrets.hash(current),
                        AuthenticationSecrets.hash(older),
                        NOW.plus(Duration.ofDays(1)),
                        NOW.minus(TOLERANCE).minusSeconds(1),
                        false));
        return family + "." + older;
    }

    private double revocations() {
        return meters.get(METER).counter().count();
    }

    @Test
    void reuseRevocationsAreVisibleAtZeroWithoutTag() {
        useCase(TOLERANCE, meters);

        var counter = meters.get(METER).counter();

        assertThat(counter.count()).as("count").isZero();
        assertThat(counter.getId().getTags()).as("tags").isEmpty();
    }

    @Test
    void replayedSecretThatRevokesTheFamilyIsCounted() {
        String cookie = familyReplayedAfterTheTolerance();
        when(transactions.revokeReplayed(
                        family, AuthenticationSecrets.hash(older), NOW.minus(TOLERANCE)))
                .thenReturn(true);

        assertThatThrownBy(() -> useCase().resume(cookie))
                .as("resume")
                .isInstanceOfSatisfying(
                        AuthenticationFailedException.class,
                        ResumeSessionUseCaseTest::isRefusal);

        assertThat(revocations()).as("revocations").isEqualTo(1);
    }

    @Test
    void replayedSecretThatAConcurrentRequestAlreadyRevokedIsNotCounted() {
        String cookie = familyReplayedAfterTheTolerance();
        when(transactions.revokeReplayed(any(), any(), any())).thenReturn(false);

        assertThatThrownBy(() -> useCase().resume(cookie))
                .as("resume")
                .isInstanceOfSatisfying(
                        AuthenticationFailedException.class,
                        ResumeSessionUseCaseTest::isRefusal);

        assertThat(revocations()).as("revocations").isZero();
    }

    @Test
    void unknownSecretOfALiveFamilyIsRefusedWithoutBeingCounted() {
        familyReplayedAfterTheTolerance();
        String cookie = family + "." + AuthenticationSecrets.generate();

        assertThatThrownBy(() -> useCase().resume(cookie))
                .as("resume")
                .isInstanceOfSatisfying(
                        AuthenticationFailedException.class,
                        ResumeSessionUseCaseTest::isRefusal);

        assertThat(revocations()).as("revocations").isZero();
        verify(transactions, never()).revokeReplayed(any(), any(), any());
    }

    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("unreadableCookies")
    void unreadableCookieIsRefusedAsAReconnectionBeforeAnyLookup(String cookie) {
        assertThatThrownBy(() -> useCase().resume(cookie))
                .as("resume")
                .isInstanceOfSatisfying(
                        AuthenticationFailedException.class,
                        ResumeSessionUseCaseTest::isRefusal);

        assertThat(reconnections.lookups()).as("lookups").isZero();
    }

    static Stream<Arguments> unreadableCookies() {
        return Stream.of(
                argumentSet("absent", (String) null),
                argumentSet("vide", ""),
                argumentSet("sans secret", UUID.randomUUID().toString()));
    }

    private static void isRefusal(AuthenticationFailedException refusal) {
        assertThat(refusal.reason())
                .as("reason")
                .isEqualTo(AuthenticationFailedException.Reason.RECONNECTION_REFUSED);
    }
}
