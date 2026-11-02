package fr.emploigo.auth.infrastructure.session;

import fr.emploigo.auth.application.session.ReconnectionRepository.Reconnection;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.UUID;

/**
 * A reconnection family. A row is new until it is saved, so that an insert of an existing id fails
 * instead of overwriting the family.
 */
@Entity
@Table(name = "auth_reconnections")
public class ReconnectionJpaEntity implements Persistable<UUID> {
    @Id
    private UUID id;

    @Column(name = "account_id", nullable = false)
    private long accountId;

    @Column(name = "credential_version", nullable = false)
    private long credentialVersion;

    @Column(name = "secret_hash", nullable = false, length = 64)
    private String secretHash;

    @Column(name = "previous_hash", length = 64)
    private String previousHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "rotated_at", nullable = false)
    private Instant rotatedAt;

    @Column(nullable = false)
    private boolean revoked;

    @Transient private boolean fresh;

    protected ReconnectionJpaEntity() {}

    /** A family issued now: it has no previous secret yet and is not revoked. */
    static ReconnectionJpaEntity issued(
            UUID id,
            long accountId,
            long version,
            String hash,
            Instant expiresAt,
            Instant issuedAt) {
        var row = new ReconnectionJpaEntity();
        row.id = id;
        row.accountId = accountId;
        row.credentialVersion = version;
        row.secretHash = hash;
        row.expiresAt = expiresAt;
        row.rotatedAt = issuedAt;
        row.revoked = false;
        row.fresh = true;
        return row;
    }

    Reconnection toDomain() {
        return new Reconnection(
                id,
                accountId,
                credentialVersion,
                secretHash,
                previousHash,
                expiresAt,
                rotatedAt,
                revoked);
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
