package fr.emploigo.auth.infrastructure.google;

import fr.emploigo.config.ApiOnly;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;

@ApiOnly
interface SpringDataGoogleVerificationRepository
        extends JpaRepository<GoogleVerificationJpaEntity, UUID> {
    boolean existsBySubjectAndEmailKeyAndStatusAndExpiresAtAfter(
            String subject, String emailKey, String status, Instant now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
            nativeQuery = true,
            value =
                    """
                    UPDATE auth_google_verifications
                    SET status = 'REPLACED'
                    WHERE subject = :subject AND email_key = :emailKey AND status = 'PENDING'
                    """)
    void replacePending(@Param("subject") String subject, @Param("emailKey") String emailKey);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
            nativeQuery = true,
            value =
                    """
                    INSERT INTO auth_google_verifications(
                        id,subject,email_key,token_hash,key_id,status,expires_at)
                    VALUES (:id,:subject,:emailKey,:tokenHash,:keyId,'PENDING',:expiresAt)
                    ON CONFLICT (subject, email_key) WHERE status = 'PENDING' DO NOTHING
                    """)
    int insert(
            @Param("id") UUID id,
            @Param("subject") String subject,
            @Param("emailKey") String emailKey,
            @Param("tokenHash") String tokenHash,
            @Param("keyId") String keyId,
            @Param("expiresAt") Instant expiresAt);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
            nativeQuery = true,
            value =
                    """
                    UPDATE auth_google_verifications
                    SET status = 'USED'
                    WHERE id = :id AND status = 'PENDING' AND expires_at > :now
                    """)
    int confirm(@Param("id") UUID id, @Param("now") Instant now);
}
