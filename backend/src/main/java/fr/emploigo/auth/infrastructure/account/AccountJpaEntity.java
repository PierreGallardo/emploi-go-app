package fr.emploigo.auth.infrastructure.account;

import fr.emploigo.auth.domain.Account;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.Generated;

import java.util.UUID;

@Entity
@Table(name = "auth_accounts")
public class AccountJpaEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // The database draws it; Hibernate reads it back after the insert.
    @Generated
    @Column(name = "public_id", nullable = false, insertable = false, updatable = false)
    private UUID publicId;

    @Column(length = 32, unique = true)
    private String username;

    @Column(name = "email_key", nullable = false, length = 320, unique = true)
    private String emailKey;

    @Column(name = "password_hash", length = 512)
    private String passwordHash;

    @Column(name = "email_verified", nullable = false)
    private boolean emailVerified;

    @Column(nullable = false)
    private boolean enabled;

    @Column(nullable = false, length = 16)
    private String role;

    @Column(name = "credential_version", nullable = false)
    private long credentialVersion;

    protected AccountJpaEntity() {}

    private AccountJpaEntity(Account account) {
        this.id = account.id();
        this.publicId = account.publicId();
        this.username = account.username();
        this.emailKey = account.emailKey();
        this.passwordHash = account.passwordHash();
        this.emailVerified = account.emailVerified();
        this.enabled = account.enabled();
        this.role = account.role();
        this.credentialVersion = account.credentialVersion();
    }

    static AccountJpaEntity from(Account account) {
        return new AccountJpaEntity(account);
    }

    Account toDomain() {
        return new Account(
                id,
                publicId,
                username,
                emailKey,
                passwordHash,
                emailVerified,
                enabled,
                role,
                credentialVersion);
    }
}
