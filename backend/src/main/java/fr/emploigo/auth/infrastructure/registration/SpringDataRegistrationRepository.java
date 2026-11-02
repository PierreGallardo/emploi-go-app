package fr.emploigo.auth.infrastructure.registration;

import fr.emploigo.config.ApiOnly;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@ApiOnly
interface SpringDataRegistrationRepository extends JpaRepository<RegistrationJpaEntity, UUID> {
    Optional<RegistrationJpaEntity> findFirstByEmailKeyAndStatus(String emailKey, String status);

    @Query(
            nativeQuery = true,
            value =
                    """
                    SELECT id, email_key, password_hash, confirmation_key_hash, code_failures,
                           codes_blocked_until, expires_at, status, account_id
                    FROM auth_registrations
                    WHERE id = :id
                    FOR UPDATE
                    """)
    Optional<RegistrationJpaEntity> selectByIdForUpdate(@Param("id") UUID id);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
            nativeQuery = true,
            value =
                    """
                    UPDATE auth_registrations
                    SET status = 'EXPIRED', email_key = NULL, password_hash = NULL,
                        confirmation_key_hash = NULL
                    WHERE status = 'PENDING' AND expires_at <= :now AND email_key = :email
                    """)
    void expireConflicts(@Param("email") String email, @Param("now") Instant now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
            nativeQuery = true,
            value =
                    """
                    UPDATE auth_registrations
                    SET status = 'EXPIRED', email_key = NULL, password_hash = NULL,
                        confirmation_key_hash = NULL
                    WHERE status = 'PENDING' AND email_key = :email
                    """)
    void expirePending(@Param("email") String email);

    @Query(
            nativeQuery = true,
            value =
                    """
                    UPDATE auth_registrations SET code_failures = code_failures + 1
                    WHERE id = :id
                    RETURNING code_failures
                    """)
    int incrementCodeFailures(@Param("id") UUID id);
}
