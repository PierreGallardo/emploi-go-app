package fr.emploigo.auth.infrastructure.google;

import fr.emploigo.config.ApiOnly;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;

@ApiOnly
interface SpringDataGooglePendingRepository extends JpaRepository<GooglePendingJpaEntity, UUID> {
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
            nativeQuery = true,
            value =
                    """
                    INSERT INTO auth_google_pending(
                        id,browser_hash,flow_hash,subject,email_key,authoritative_email,account_id,
                        credential_version,family_id,expires_at)
                    VALUES (:id,:browserHash,:flowHash,:subject,:emailKey,:authoritativeEmail,
                        :accountId,:credentialVersion,:familyId,:expiresAt)
                    """)
    void insert(
            @Param("id") UUID id,
            @Param("browserHash") String browserHash,
            @Param("flowHash") String flowHash,
            @Param("subject") String subject,
            @Param("emailKey") String emailKey,
            @Param("authoritativeEmail") boolean authoritativeEmail,
            @Param("accountId") Long accountId,
            @Param("credentialVersion") Long credentialVersion,
            @Param("familyId") UUID familyId,
            @Param("expiresAt") Instant expiresAt);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
            nativeQuery = true,
            value =
                    """
                    UPDATE auth_google_pending
                    SET account_id = :accountId,credential_version = :credentialVersion,
                        family_id = :familyId,access_verified = TRUE
                    WHERE id = :id
                    """)
    void access(
            @Param("id") UUID id,
            @Param("accountId") long accountId,
            @Param("credentialVersion") long credentialVersion,
            @Param("familyId") UUID familyId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
            nativeQuery = true,
            value =
                    """
                    UPDATE auth_google_pending SET consumed_at = :now
                    WHERE id = :id
                    """)
    void finish(@Param("id") UUID id, @Param("now") Instant now);
}
