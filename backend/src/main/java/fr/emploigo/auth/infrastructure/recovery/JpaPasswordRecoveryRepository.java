package fr.emploigo.auth.infrastructure.recovery;

import fr.emploigo.auth.application.recovery.PasswordRecoveryRepository;
import fr.emploigo.auth.infrastructure.persistence.UniqueViolations;
import fr.emploigo.config.ApiOnly;

import jakarta.persistence.EntityManager;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.UUID;

/**
 * The password resets in PostgreSQL. Every row read is a fresh copy, detached after its conversion,
 * and every write reaches the database before the method returns.
 */
@Repository
@ApiOnly
public class JpaPasswordRecoveryRepository implements PasswordRecoveryRepository {
    private final SpringDataPasswordRecoveryRepository rows;
    private final EntityManager entities;

    public JpaPasswordRecoveryRepository(
            SpringDataPasswordRecoveryRepository rows, EntityManager entities) {
        this.rows = rows;
        this.entities = entities;
    }

    @Override
    public Reset reset(UUID id) {
        return rows.findById(id).map(this::detached).orElse(null);
    }

    @Override
    public void replace(long accountId) {
        rows.replacePending(accountId);
    }

    @Override
    public void insert(
            UUID id,
            long accountId,
            long version,
            String hash,
            String keyId,
            Instant expiry) {
        var row = PasswordResetJpaEntity.pending(id, accountId, version, hash, keyId, expiry);
        try {
            entities.detach(rows.saveAndFlush(row));
        } catch (DataIntegrityViolationException error) {
            throw uniqueViolationAsDuplicateKey(error);
        }
    }

    @Override
    public boolean consume(Reset reset, Instant now) {
        return rows.markUsed(reset.id(), now) == 1;
    }

    /**
     * Detaches the row: a managed entity would stay in the persistence context, and a locked read
     * that follows would return that copy rather than the row as committed.
     */
    private Reset detached(PasswordResetJpaEntity row) {
        entities.detach(row);
        return row.toReset();
    }

    /**
     * JPA reports a unique violation as a generic integrity failure; it is restored here as the
     * {@link DuplicateKeyException} that the callers receive from JDBC.
     */
    private static DataIntegrityViolationException uniqueViolationAsDuplicateKey(
            DataIntegrityViolationException error) {
        return UniqueViolations.occurred(error)
                ? new DuplicateKeyException(error.getMessage(), error)
                : error;
    }
}
