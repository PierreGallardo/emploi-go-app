package fr.emploigo.auth.infrastructure.google;

import fr.emploigo.auth.application.google.GoogleRepository;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "auth_google_verifications")
public class GoogleVerificationJpaEntity {
    @Id
    private UUID id;

    @Column(nullable = false, length = 255)
    private String subject;

    @Column(name = "email_key", nullable = false, length = 320)
    private String emailKey;

    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @Column(name = "key_id", nullable = false, length = 64)
    private String keyId;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    protected GoogleVerificationJpaEntity() {}

    GoogleRepository.Verification toVerification() {
        return new GoogleRepository.Verification(
                id, subject, emailKey, tokenHash, keyId, status, expiresAt);
    }
}
