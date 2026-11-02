package fr.emploigo.auth.infrastructure.recovery;

import fr.emploigo.auth.application.recovery.PasswordRecoveryRepository.Reset;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.UUID;

/**
 * A row of the password resets. A row is new until it is saved, so that an insert of an existing
 * identifier fails instead of updating the row.
 */
@Entity
@Table(name = "auth_password_resets")
public class PasswordResetJpaEntity implements Persistable<UUID> {
    @Id
    @Column(nullable = false)
    private UUID id;

    @Column(name = "account_id", nullable = false)
    private long accountId;

    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @Column(name = "key_id", nullable = false, length = 64)
    private String keyId;

    @Column(name = "credential_version", nullable = false)
    private long credentialVersion;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    @Transient private boolean fresh;

    protected PasswordResetJpaEntity() {}

    /** A reset that a request creates: it is pending, and its row is new. */
    static PasswordResetJpaEntity pending(
            UUID id,
            long accountId,
            long version,
            String hash,
            String keyId,
            Instant expiry) {
        var row = new PasswordResetJpaEntity();
        row.id = id;
        row.accountId = accountId;
        row.tokenHash = hash;
        row.keyId = keyId;
        row.credentialVersion = version;
        row.expiresAt = expiry;
        row.status = "PENDING";
        row.fresh = true;
        return row;
    }

    Reset toReset() {
        return new Reset(
                id, accountId, tokenHash, keyId, credentialVersion, expiresAt, status, consumedAt);
    }

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return fresh;
    }
}
