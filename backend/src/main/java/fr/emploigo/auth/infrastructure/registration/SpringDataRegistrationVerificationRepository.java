package fr.emploigo.auth.infrastructure.registration;

import fr.emploigo.config.ApiOnly;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

@ApiOnly
interface SpringDataRegistrationVerificationRepository
        extends JpaRepository<RegistrationVerificationJpaEntity, UUID> {
    List<RegistrationVerificationJpaEntity> findTop20ByRegistrationIdOrderByCreatedAtDescIdAsc(
            UUID registrationId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
            nativeQuery = true,
            value =
                    """
                    UPDATE auth_verifications
                    SET status = 'REPLACED'
                    WHERE registration_id = :registrationId AND status = 'PENDING'
                    """)
    void replaceVerification(@Param("registrationId") UUID registrationId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
            nativeQuery = true,
            value =
                    """
                    UPDATE auth_verifications SET failed_attempts = failed_attempts + 1
                    WHERE id = :id
                    """)
    void incrementFailedAttempts(@Param("id") UUID id);
}
