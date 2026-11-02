package fr.emploigo.auth.infrastructure.google;

import fr.emploigo.auth.application.google.GoogleRepository;
import fr.emploigo.auth.domain.GoogleIdentity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "auth_google_pending")
public class GooglePendingJpaEntity {
    @Id
    private UUID id;

    @Column(name = "browser_hash", nullable = false, length = 64)
    private String browserHash;

    @Column(name = "flow_hash", nullable = false, length = 64)
    private String flowHash;

    @Column(nullable = false, length = 255)
    private String subject;

    @Column(name = "email_key", nullable = false, length = 320)
    private String emailKey;

    @Column(name = "authoritative_email", nullable = false)
    private boolean authoritativeEmail;

    @Column(name = "access_verified", nullable = false)
    private boolean accessVerified;

    @Column(name = "account_id")
    private Long accountId;

    @Column(name = "credential_version")
    private Long credentialVersion;

    @Column(name = "family_id")
    private UUID familyId;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    protected GooglePendingJpaEntity() {}

    GoogleRepository.Pending toPending() {
        return new GoogleRepository.Pending(
                id,
                browserHash,
                flowHash,
                new GoogleIdentity(subject, emailKey, authoritativeEmail),
                new GoogleRepository.Scope(accountId, credentialVersion, familyId),
                accessVerified,
                expiresAt,
                consumedAt);
    }
}
