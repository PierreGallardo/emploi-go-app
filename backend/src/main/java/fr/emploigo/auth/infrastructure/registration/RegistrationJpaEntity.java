package fr.emploigo.auth.infrastructure.registration;

import fr.emploigo.auth.application.registration.RegistrationRepository;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "auth_registrations")
public class RegistrationJpaEntity {
    @Id
    private UUID id;

    @Column(name = "email_key", length = 320)
    private String emailKey;

    @Column(name = "password_hash", length = 512)
    private String passwordHash;

    @Column(name = "confirmation_key_hash", length = 64)
    private String confirmationKeyHash;

    @Column(name = "code_failures", nullable = false)
    private int codeFailures;

    @Column(name = "codes_blocked_until")
    private Instant codesBlockedUntil;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "account_id")
    private Long accountId;

    protected RegistrationJpaEntity() {}

    private RegistrationJpaEntity(
            UUID id,
            String emailKey,
            String passwordHash,
            String confirmationKeyHash,
            Instant expiresAt) {
        this.id = id;
        this.emailKey = emailKey;
        this.passwordHash = passwordHash;
        this.confirmationKeyHash = confirmationKeyHash;
        this.status = "PENDING";
        this.expiresAt = expiresAt;
    }

    static RegistrationJpaEntity pending(
            UUID id,
            String emailKey,
            String passwordHash,
            String confirmationKeyHash,
            Instant expiresAt) {
        return new RegistrationJpaEntity(
                id, emailKey, passwordHash, confirmationKeyHash, expiresAt);
    }

    void restart(String passwordHash, String confirmationKeyHash) {
        this.passwordHash = passwordHash;
        this.confirmationKeyHash = confirmationKeyHash;
    }

    void extend(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    void blockCodesUntil(Instant until) {
        this.codesBlockedUntil = until;
    }

    void consume(long accountId) {
        this.status = "CONFIRMED";
        this.accountId = accountId;
        this.emailKey = null;
        this.passwordHash = null;
    }

    RegistrationRepository.Pending toPending() {
        return new RegistrationRepository.Pending(
                id,
                emailKey,
                passwordHash,
                confirmationKeyHash,
                codeFailures,
                codesBlockedUntil,
                expiresAt,
                status);
    }
}
