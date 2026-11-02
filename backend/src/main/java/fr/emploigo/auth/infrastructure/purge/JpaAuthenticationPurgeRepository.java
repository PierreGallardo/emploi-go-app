package fr.emploigo.auth.infrastructure.purge;

import fr.emploigo.auth.application.purge.AuthenticationPurgeRepository;
import fr.emploigo.config.WorkerOnly;

import jakarta.persistence.EntityManager;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;

/**
 * Each batch is one native statement in its own transaction, opened here rather than by a proxy,
 * so that an instance built outside Spring runs its batches too.
 */
@Repository
@WorkerOnly
public class JpaAuthenticationPurgeRepository implements AuthenticationPurgeRepository {

    // No statement reads more rows than it handles. A row changed while a statement waited for its
    // lock is checked again: the expiry of registrations skips the rows another transaction holds,
    // and the other updates repeat their inner conditions outside.
    static final String EXPIRE_REGISTRATIONS =
            """
            UPDATE auth_registrations
            SET status = 'EXPIRED', email_key = NULL, password_hash = NULL,
                confirmation_key_hash = NULL
            WHERE id IN (
                SELECT id FROM auth_registrations
                WHERE status = 'PENDING' AND expires_at <= ?
                ORDER BY expires_at LIMIT %d FOR UPDATE SKIP LOCKED)
            """
                    .formatted(BATCH_SIZE);
    static final String REGISTRATIONS =
            """
            DELETE FROM auth_registrations
            WHERE id IN (
                SELECT id FROM auth_registrations
                WHERE status <> 'PENDING' AND expires_at < ? LIMIT %d)
            """
                    .formatted(BATCH_SIZE);
    static final String PASSWORD_RESETS =
            """
            DELETE FROM auth_password_resets
            WHERE id IN (SELECT id FROM auth_password_resets WHERE expires_at < ? LIMIT %d)
            """
                    .formatted(BATCH_SIZE);
    static final String GOOGLE_FLOWS =
            """
            DELETE FROM auth_google_flows
            WHERE state_hash IN (
                SELECT state_hash FROM auth_google_flows
                WHERE expires_at < ? LIMIT %d)
            """
                    .formatted(BATCH_SIZE);
    static final String GOOGLE_PENDING =
            """
            DELETE FROM auth_google_pending
            WHERE id IN (SELECT id FROM auth_google_pending WHERE expires_at < ? LIMIT %d)
            """
                    .formatted(BATCH_SIZE);
    static final String GOOGLE_VERIFICATIONS =
            """
            DELETE FROM auth_google_verifications
            WHERE id IN (SELECT id FROM auth_google_verifications WHERE expires_at < ? LIMIT %d)
            """
                    .formatted(BATCH_SIZE);
    // The expiry is repeated outside, so that a family renewed while the statement waited for its
    // lock is kept. The worker
    // cannot lock rows in advance, so it waits for the locks the API holds.
    static final String RECONNECTIONS =
            """
            DELETE FROM auth_reconnections
            WHERE expires_at < ? AND id IN (
                SELECT id FROM auth_reconnections
                WHERE expires_at < ? LIMIT %d)
            """
                    .formatted(BATCH_SIZE);
    private final EntityManager entities;
    private final TransactionTemplate batches;

    public JpaAuthenticationPurgeRepository(
            EntityManager entities, PlatformTransactionManager transactions) {
        this.entities = entities;
        this.batches = new TransactionTemplate(transactions);
    }

    @Override
    public int expireRegistrations(Instant expiredBy) {
        return update(EXPIRE_REGISTRATIONS, Timestamp.from(expiredBy));
    }

    @Override
    public int deleteRegistrations(Instant expiredBefore) {
        return update(REGISTRATIONS, Timestamp.from(expiredBefore));
    }

    @Override
    public int deletePasswordResets(Instant expiredBefore) {
        return update(PASSWORD_RESETS, Timestamp.from(expiredBefore));
    }

    @Override
    public int deleteGoogleFlows(Instant expiredBefore) {
        return update(GOOGLE_FLOWS, Timestamp.from(expiredBefore));
    }

    @Override
    public int deleteGooglePending(Instant expiredBefore) {
        return update(GOOGLE_PENDING, Timestamp.from(expiredBefore));
    }

    @Override
    public int deleteGoogleVerifications(Instant expiredBefore) {
        return update(GOOGLE_VERIFICATIONS, Timestamp.from(expiredBefore));
    }

    @Override
    public int deleteReconnections(Instant expiredBefore) {
        return update(RECONNECTIONS, Timestamp.from(expiredBefore), Timestamp.from(expiredBefore));
    }

    /** Runs one batch in its own transaction, and returns the rows it changed. */
    private int update(String statement, Object... parameters) {
        Integer rows =
                batches.execute(
                        status -> {
                            var query = entities.createNativeQuery(statement);
                            for (int position = 0; position < parameters.length; position++)
                                query.setParameter(position + 1, parameters[position]);
                            return query.executeUpdate();
                        });
        return rows;
    }
}
