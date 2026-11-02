package fr.emploigo.auth.infrastructure.registration;

import fr.emploigo.auth.application.registration.RegistrationRepository;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "auth_verifications")
public class RegistrationVerificationJpaEntity {
    @Id
    private UUID id;

    @Column(name = "registration_id", nullable = false)
    private UUID registrationId;

    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @Column(name = "key_id", nullable = false, length = 64)
    private String keyId;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(nullable = false, length = 16)
    private String status;

    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "failed_attempts", nullable = false)
    private int failedAttempts;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected RegistrationVerificationJpaEntity() {}

    private RegistrationVerificationJpaEntity(
            UUID id,
            UUID registrationId,
            String tokenHash,
            String keyId,
            Instant createdAt,
            Instant expiresAt) {
        this.id = id;
        this.registrationId = registrationId;
        this.tokenHash = tokenHash;
        this.keyId = keyId;
        this.expiresAt = expiresAt;
        this.status = "PENDING";
        this.failedAttempts = 0;
        this.createdAt = createdAt;
    }

    static RegistrationVerificationJpaEntity issued(
            UUID id,
            UUID registrationId,
            String tokenHash,
            String keyId,
            Instant createdAt,
            Instant expiresAt) {
        return new RegistrationVerificationJpaEntity(
                id, registrationId, tokenHash, keyId, createdAt, expiresAt);
    }

    void markUsed() {
        this.status = "USED";
    }

    void spendAttempts(int attempts) {
        this.failedAttempts = attempts;
    }

    RegistrationRepository.Verification toVerification() {
        return new RegistrationRepository.Verification(
                id, registrationId, tokenHash, keyId, expiresAt, status, failedAttempts);
    }
}
