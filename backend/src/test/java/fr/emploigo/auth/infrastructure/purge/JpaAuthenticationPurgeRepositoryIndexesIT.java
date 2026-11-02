package fr.emploigo.auth.infrastructure.purge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import fr.emploigo.auth.application.AuthenticationIntegrationTest;
import fr.emploigo.support.QueryPlans;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Each statement of the purge reads its table through its index, never the whole table (see {@link
 * QueryPlans}).
 */
class JpaAuthenticationPurgeRepositoryIndexesIT extends AuthenticationIntegrationTest {
    private static final Timestamp WHEN = Timestamp.from(Instant.parse("2026-10-16T10:00:00Z"));

    static Stream<Arguments> statements() {
        return Stream.of(
                argumentSet(
                        "pending registrations",
                        JpaAuthenticationPurgeRepository.EXPIRE_REGISTRATIONS,
                        List.of(WHEN),
                        "auth_registrations_expiry_idx"),
                argumentSet(
                        "registrations that are not pending",
                        JpaAuthenticationPurgeRepository.REGISTRATIONS,
                        List.of(WHEN),
                        "auth_registrations_retention_idx"),
                // What the database runs for each registration deleted, to delete its links with
                // it.
                argumentSet(
                        "links of a registration deleted with it",
                        "DELETE FROM auth_verifications WHERE registration_id = ?",
                        List.of(UUID.randomUUID()),
                        "auth_verifications_registration_idx"),
                argumentSet(
                        "requests to recover a password",
                        JpaAuthenticationPurgeRepository.PASSWORD_RESETS,
                        List.of(WHEN),
                        "auth_password_resets_expiry_idx"),
                argumentSet(
                        "starts of the Google authentication",
                        JpaAuthenticationPurgeRepository.GOOGLE_FLOWS,
                        List.of(WHEN),
                        "auth_google_flows_expiry_idx"),
                argumentSet(
                        "pending Google authentications",
                        JpaAuthenticationPurgeRepository.GOOGLE_PENDING,
                        List.of(WHEN),
                        "auth_google_pending_expiry_idx"),
                argumentSet(
                        "verifications of Google",
                        JpaAuthenticationPurgeRepository.GOOGLE_VERIFICATIONS,
                        List.of(WHEN),
                        "auth_google_verifications_expiry_idx"),
                argumentSet(
                        "families of reconnection",
                        JpaAuthenticationPurgeRepository.RECONNECTIONS,
                        List.of(WHEN, WHEN),
                        "auth_reconnections_expiry_idx"));
    }

    @ParameterizedTest
    @MethodSource("statements")
    void eachStatementReadsItsTableThroughItsIndex(
            String statement, List<Object> arguments, String index) {
        String plan = QueryPlans.of(jdbc, statement, arguments);

        assertThat(plan).as("plan").doesNotContain("Seq Scan").contains(index);
    }
}
