package fr.emploigo.auth.infrastructure.google;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "auth_google_flows")
public class GoogleFlowJpaEntity {
    @Id
    @Column(name = "state_hash", nullable = false, length = 64)
    private String stateHash;

    @Column(name = "browser_hash", nullable = false, length = 64)
    private String browserHash;

    @Column(name = "account_id")
    private Long accountId;

    @Column(name = "credential_version")
    private Long credentialVersion;

    @Column(name = "family_id")
    private UUID familyId;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(nullable = false)
    private boolean consumed;

    @Column(nullable = false)
    private boolean superseded;

    @Column(name = "issued_family_id")
    private UUID issuedFamilyId;

    protected GoogleFlowJpaEntity() {}

    String browserHash() {
        return browserHash;
    }
}
