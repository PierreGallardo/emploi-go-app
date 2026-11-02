package fr.emploigo.auth.infrastructure.registration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import fr.emploigo.auth.application.AuthenticationIntegrationTest;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

class RegistrationConstraintsIT extends AuthenticationIntegrationTest {
    private static final Timestamp NOW = Timestamp.from(Instant.parse("2026-10-16T10:00:00Z"));

    @ParameterizedTest
    @CsvSource(
            nullValues = "NULL",
            value = {
                "CONFIRMED, jeune@example.invalid, NULL",
                "CONFIRMED, NULL, hash",
                "EXPIRED, jeune@example.invalid, NULL",
                "EXPIRED, NULL, hash"
            })
    void theDatabaseRefusesAFinishedRegistrationThatKeepsItsData(
            String status, String emailKey, String passwordHash) {
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        """
                                        INSERT INTO auth_registrations(
                                            id, email_key, password_hash, expires_at, status)
                                        VALUES (?, ?, ?, ?, ?)
                                        """,
                                        UUID.randomUUID(),
                                        emailKey,
                                        passwordHash,
                                        NOW,
                                        status))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("auth_registrations_erased_check");
    }
}
