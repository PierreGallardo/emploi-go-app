package fr.emploigo.auth.infrastructure.recovery;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import fr.emploigo.auth.application.AuthenticationIntegrationTest;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

class PasswordResetConstraintsIT extends AuthenticationIntegrationTest {
    private static final Timestamp NOW = Timestamp.from(Instant.parse("2026-10-16T10:00:00Z"));

    @Test
    void theDatabaseRefusesAUsedResetWithoutConsumptionTime() {
        long account =
                jdbc.queryForObject(
                        """
                        INSERT INTO auth_accounts(username, email_key, email_verified, enabled)
                        VALUES ('jeune', 'jeune@example.invalid', TRUE, TRUE)
                        RETURNING id
                        """,
                        Long.class);

        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        """
                                        INSERT INTO auth_password_resets(
                                            id, account_id, token_hash, key_id, credential_version,
                                            expires_at, status)
                                        VALUES (?, ?, 'a', 'key', 0, ?, 'USED')
                                        """,
                                        UUID.randomUUID(),
                                        account,
                                        NOW))
                .as("used reset without consumption time")
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("auth_password_resets_consumed_check");
    }
}
