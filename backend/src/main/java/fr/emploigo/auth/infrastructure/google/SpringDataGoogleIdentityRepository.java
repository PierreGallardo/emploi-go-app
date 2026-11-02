package fr.emploigo.auth.infrastructure.google;

import fr.emploigo.config.ApiOnly;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

@ApiOnly
interface SpringDataGoogleIdentityRepository
        extends JpaRepository<GoogleIdentityJpaEntity, String> {
    Optional<GoogleIdentityJpaEntity> findByAccountId(long accountId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
            nativeQuery = true,
            value =
                    """
                    INSERT INTO auth_google_identities(subject,account_id)
                    VALUES (:subject,:accountId)
                    """)
    void insert(@Param("subject") String subject, @Param("accountId") long accountId);
}
