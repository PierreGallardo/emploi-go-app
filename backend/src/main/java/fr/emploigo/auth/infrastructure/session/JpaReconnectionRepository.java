package fr.emploigo.auth.infrastructure.session;

import fr.emploigo.auth.application.session.ReconnectionRepository;
import fr.emploigo.config.ApiOnly;

import jakarta.persistence.EntityManager;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * The reconnection families in PostgreSQL. Every row read is a fresh copy, detached after its
 * conversion, and every write reaches the database before the method returns.
 */
@Repository
@ApiOnly
public class JpaReconnectionRepository implements ReconnectionRepository {
    private final SpringDataReconnectionRepository rows;
    private final EntityManager entities;

    public JpaReconnectionRepository(
            SpringDataReconnectionRepository rows, EntityManager entities) {
        this.rows = rows;
        this.entities = entities;
    }

    @Override
    public void insert(
            UUID id,
            long accountId,
            long version,
            String hash,
            Instant expiresAt,
            Instant issuedAt) {
        entities.detach(
                rows.saveAndFlush(
                        ReconnectionJpaEntity.issued(
                                id, accountId, version, hash, expiresAt, issuedAt)));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Reconnection lock(UUID id) {
        return rows.selectByIdForUpdate(id).map(this::detached).orElse(null);
    }

    @Override
    public Reconnection find(UUID id) {
        return rows.findById(id).map(this::detached).orElse(null);
    }

    @Override
    public void rotate(Reconnection previous, String hash, Instant expiresAt, Instant issuedAt) {
        rows.rotate(previous.id(), hash, expiresAt, issuedAt);
    }

    @Override
    public boolean active(UUID id, long accountId, long version, Instant now) {
        return rows.existsByIdAndAccountIdAndCredentialVersionAndRevokedFalseAndExpiresAtAfter(
                id, accountId, version, now);
    }

    @Override
    public void revoke(UUID id) {
        rows.revoke(id);
    }

    @Override
    public boolean revokeReplayed(UUID id, String previousHash, Instant rotatedBefore) {
        return rows.revokeReplayed(id, previousHash, rotatedBefore) == 1;
    }

    /**
     * Detaches the row: a managed entity would stay in the persistence context, and a locked read
     * that follows would return that copy rather than the row as committed.
     */
    private Reconnection detached(ReconnectionJpaEntity row) {
        entities.detach(row);
        return row.toDomain();
    }
}
