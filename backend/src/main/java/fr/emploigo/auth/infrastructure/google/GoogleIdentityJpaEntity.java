package fr.emploigo.auth.infrastructure.google;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "auth_google_identities")
public class GoogleIdentityJpaEntity {
    @Id
    @Column(length = 255)
    private String subject;

    @Column(name = "account_id", nullable = false, unique = true)
    private long accountId;

    protected GoogleIdentityJpaEntity() {}

    String subject() {
        return subject;
    }

    long accountId() {
        return accountId;
    }
}
