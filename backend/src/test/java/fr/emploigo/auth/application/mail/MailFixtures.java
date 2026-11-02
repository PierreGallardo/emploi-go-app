package fr.emploigo.auth.application.mail;

import fr.emploigo.auth.domain.MailFlow;

import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/**
 * Rows that an authentication email depends on, written straight into PostgreSQL: the operation a
 * link opens. Also builds the email itself.
 */
public final class MailFixtures {
    public static final String ADDRESS = "jeune@example.invalid";
    public static final String SENDER = "Emploi Go <ne-pas-repondre@example.invalid>";
    public static final String ORIGIN = "https://emploi-go.example.invalid";

    private MailFixtures() {}

    public static UUID pendingVerification(JdbcTemplate jdbc, Instant now, String keyId) {
        UUID registration = UUID.randomUUID();
        UUID verification = UUID.randomUUID();
        var later = Timestamp.from(now.plusSeconds(3600));
        jdbc.update(
                """
                INSERT INTO auth_registrations(
                    id, email_key, password_hash, confirmation_key_hash, expires_at, status)
                VALUES (?, ?, 'hash', 'key', ?, 'PENDING')
                """,
                registration,
                ADDRESS,
                later);
        jdbc.update(
                """
                INSERT INTO auth_verifications(
                    id, registration_id, token_hash, key_id, expires_at, status, created_at)
                VALUES (?, ?, 'hash', ?, ?, 'PENDING', ?)
                """,
                verification,
                registration,
                keyId,
                later,
                Timestamp.from(now));
        return verification;
    }

    public static UUID pendingReset(JdbcTemplate jdbc, Instant now, String keyId) {
        long account =
                jdbc.queryForObject(
                        """
                        INSERT INTO auth_accounts(
                            username, email_key, password_hash, email_verified, enabled)
                        VALUES ('jeune', ?, 'hash', TRUE, TRUE) RETURNING id
                        """,
                        Long.class,
                        ADDRESS);
        UUID reset = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO auth_password_resets(
                    id, account_id, token_hash, key_id, credential_version, expires_at, status)
                VALUES (?, ?, 'hash', ?, 0, ?, 'PENDING')
                """,
                reset,
                account,
                keyId,
                Timestamp.from(now.plusSeconds(1800)));
        return reset;
    }

    public static UUID pendingGoogleVerification(JdbcTemplate jdbc, Instant now, String keyId) {
        UUID verification = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO auth_google_verifications(
                    id, subject, email_key, token_hash, key_id, status, expires_at)
                VALUES (?, 'subject', ?, 'hash', ?, 'PENDING', ?)
                """,
                verification,
                ADDRESS,
                keyId,
                Timestamp.from(now.plusSeconds(3600)));
        return verification;
    }

    /** An email of {@code flow} and {@code kind} that opens {@code operation}, or none. */
    public static AuthenticationMail mail(
            MailFlow flow, String kind, UUID operation, String keyId) {
        return AuthenticationMail.of(flow, kind, operation, ADDRESS, SENDER, ORIGIN, keyId);
    }

    /** An email of {@code flow} and {@code kind} that opens no operation. */
    public static AuthenticationMail mail(MailFlow flow, String kind) {
        return mail(flow, kind, null, "key-id");
    }
}
