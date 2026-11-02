package fr.emploigo.auth.infrastructure.google;

import fr.emploigo.config.ApiOnly;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@ApiOnly
interface SpringDataGoogleFlowRepository extends JpaRepository<GoogleFlowJpaEntity, String> {
    interface ConsumedFlow {
        Long getAccountId();

        Long getCredentialVersion();

        UUID getFamilyId();
    }

    boolean existsByStateHashAndConsumedTrueAndSupersededFalse(String stateHash);

    @Modifying
    @Query(nativeQuery = true, value = "SET LOCAL lock_timeout = '3s'")
    void setLockTimeout();

    @Query(nativeQuery = true, value = "SELECT pg_advisory_xact_lock(:key)")
    Object lockKey(@Param("key") long key);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
            nativeQuery = true,
            value =
                    """
                    INSERT INTO auth_google_flows(
                        state_hash,browser_hash,account_id,credential_version,family_id,expires_at)
                    VALUES (:stateHash,:browserHash,:accountId,:credentialVersion,:familyId,
                        :expiresAt)
                    """)
    void insert(
            @Param("stateHash") String stateHash,
            @Param("browserHash") String browserHash,
            @Param("accountId") Long accountId,
            @Param("credentialVersion") Long credentialVersion,
            @Param("familyId") UUID familyId,
            @Param("expiresAt") Instant expiresAt);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
            nativeQuery = true,
            value =
                    """
                    UPDATE auth_google_flows
                    SET consumed = TRUE,superseded = TRUE
                    WHERE browser_hash = :browserHash AND NOT superseded
                    """)
    void supersedeBrowser(@Param("browserHash") String browserHash);

    @Query(
            nativeQuery = true,
            value =
                    """
                    UPDATE auth_google_flows
                    SET consumed = TRUE
                    WHERE state_hash = :stateHash AND browser_hash = :browserHash
                    AND NOT consumed AND NOT superseded AND expires_at > :now
                    RETURNING account_id AS "accountId",credential_version AS "credentialVersion",
                        family_id AS "familyId"
                    """)
    List<ConsumedFlow> consume(
            @Param("stateHash") String stateHash,
            @Param("browserHash") String browserHash,
            @Param("now") Instant now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
            nativeQuery = true,
            value =
                    """
                    UPDATE auth_google_flows SET issued_family_id = :familyId
                    WHERE state_hash = :stateHash
                    """)
    void issue(@Param("stateHash") String stateHash, @Param("familyId") UUID familyId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
            nativeQuery = true,
            value =
                    """
                    UPDATE auth_reconnections
                    SET revoked = TRUE
                    WHERE id IN (
                        SELECT issued_family_id FROM auth_google_flows
                        WHERE browser_hash = :browserHash AND issued_family_id IS NOT NULL)
                    AND (CAST(:retainedFamily AS uuid) IS NULL
                        OR id <> CAST(:retainedFamily AS uuid))
                    """)
    void revokeIssuedReconnections(
            @Param("browserHash") String browserHash,
            @Param("retainedFamily") UUID retainedFamily);
}
