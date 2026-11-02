package fr.emploigo.auth.infrastructure.session;

import fr.emploigo.config.ApiOnly;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@ApiOnly
interface SpringDataReconnectionRepository extends JpaRepository<ReconnectionJpaEntity, UUID> {
    @Query(
            nativeQuery = true,
            value =
                    """
                    SELECT id, account_id, credential_version, secret_hash, previous_hash,
                        expires_at, rotated_at, revoked
                    FROM auth_reconnections
                    WHERE id = :id
                    FOR UPDATE
                    """)
    Optional<ReconnectionJpaEntity> selectByIdForUpdate(@Param("id") UUID id);

    boolean existsByIdAndAccountIdAndCredentialVersionAndRevokedFalseAndExpiresAtAfter(
            UUID id, long accountId, long credentialVersion, Instant now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query(
            nativeQuery = true,
            value =
                    """
                    UPDATE auth_reconnections
                    SET previous_hash = secret_hash, secret_hash = :hash,
                        expires_at = :expiresAt, rotated_at = :issuedAt
                    WHERE id = :id
                    """)
    void rotate(
            @Param("id") UUID id,
            @Param("hash") String hash,
            @Param("expiresAt") Instant expiresAt,
            @Param("issuedAt") Instant issuedAt);

    // Only the revoked column is written: a save would rewrite the columns read before a concurrent
    // rotation.
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query(
            nativeQuery = true,
            value =
                    """
                    UPDATE auth_reconnections
                    SET revoked = TRUE
                    WHERE id = :id
                    """)
    void revoke(@Param("id") UUID id);

    /** One conditional UPDATE: PostgreSQL rechecks the condition after the row lock. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query(
            nativeQuery = true,
            value =
                    """
                    UPDATE auth_reconnections
                    SET revoked = TRUE
                    WHERE id = :id AND previous_hash = :previousHash AND NOT revoked
                        AND rotated_at < :rotatedBefore
                    """)
    int revokeReplayed(
            @Param("id") UUID id,
            @Param("previousHash") String previousHash,
            @Param("rotatedBefore") Instant rotatedBefore);
}
