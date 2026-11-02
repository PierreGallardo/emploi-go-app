package fr.emploigo.auth.infrastructure.recovery;

import fr.emploigo.config.ApiOnly;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@ApiOnly
interface SpringDataPasswordRecoveryRepository extends JpaRepository<PasswordResetJpaEntity, UUID> {
    // REQUIRED: each statement joins the caller's transaction, so that a failure rolls back the
    // change.
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
            """
            UPDATE PasswordResetJpaEntity r
            SET r.status = 'REPLACED'
            WHERE r.accountId = :accountId AND r.status = 'PENDING'
            """)
    void replacePending(@Param("accountId") long accountId);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
            """
            UPDATE PasswordResetJpaEntity r
            SET r.status = 'USED', r.consumedAt = :consumedAt
            WHERE r.id = :id AND r.status = 'PENDING'
            """)
    int markUsed(@Param("id") UUID id, @Param("consumedAt") Instant consumedAt);
}
