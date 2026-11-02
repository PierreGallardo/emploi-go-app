package fr.emploigo.auth.infrastructure.account;

import fr.emploigo.config.ApiOnly;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

@ApiOnly
interface SpringDataAccountRepository extends JpaRepository<AccountJpaEntity, Long> {
    Optional<AccountJpaEntity> findByEmailKey(String emailKey);

    Optional<AccountJpaEntity> findByUsername(String username);

    @Query(
            nativeQuery = true,
            value =
                    """
                    SELECT id, public_id, username, email_key, password_hash,
                           email_verified, enabled, role, credential_version
                    FROM auth_accounts
                    WHERE id = :id
                    FOR NO KEY UPDATE
                    """)
    Optional<AccountJpaEntity> selectByIdForNoKeyUpdate(@Param("id") long id);

    @Query(
            nativeQuery = true,
            value =
                    """
                    SELECT id, public_id, username, email_key, password_hash,
                           email_verified, enabled, role, credential_version
                    FROM auth_accounts
                    WHERE id = :id
                    FOR UPDATE
                    """)
    Optional<AccountJpaEntity> selectByIdForUpdate(@Param("id") long id);

    boolean existsByEmailKey(String emailKey);

    boolean existsByUsername(String username);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query(
            nativeQuery = true,
            value =
                    """
                    UPDATE auth_accounts
                    SET password_hash = :passwordHash, credential_version = credential_version + 1
                    WHERE id = :id
                    """)
    void changePassword(@Param("id") long id, @Param("passwordHash") String passwordHash);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query(
            nativeQuery = true,
            value =
                    """
                    UPDATE auth_accounts SET username = :username
                    WHERE id = :id AND username IS NULL
                    """)
    int assignUsername(@Param("id") long id, @Param("username") String username);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query(
            nativeQuery = true,
            value = "UPDATE auth_accounts SET terms_accepted_at = :acceptedAt WHERE id = :id")
    void recordTermsAcceptance(@Param("id") long id, @Param("acceptedAt") Instant acceptedAt);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
            nativeQuery = true,
            value = "UPDATE auth_accounts SET last_seen_at = :openedAt WHERE id = :id")
    void recordSessionOpened(@Param("id") long id, @Param("openedAt") Instant openedAt);
}
