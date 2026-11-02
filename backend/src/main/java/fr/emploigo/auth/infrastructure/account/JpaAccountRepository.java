package fr.emploigo.auth.infrastructure.account;

import fr.emploigo.auth.application.account.AccountRepository;
import fr.emploigo.auth.domain.Account;
import fr.emploigo.auth.domain.IdentifierTakenException;
import fr.emploigo.auth.domain.IdentifierTakenException.Identifier;
import fr.emploigo.auth.infrastructure.persistence.UniqueViolations;
import fr.emploigo.config.ApiOnly;

import jakarta.persistence.EntityManager;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

@Repository
@ApiOnly
public class JpaAccountRepository implements AccountRepository {
    private static final String USERNAME_CONSTRAINT = "auth_accounts_username_key";
    private static final String EMAIL_CONSTRAINT = "auth_accounts_email_key_key";
    private final SpringDataAccountRepository rows;
    private final EntityManager entities;

    public JpaAccountRepository(SpringDataAccountRepository rows, EntityManager entities) {
        this.rows = rows;
        this.entities = entities;
    }

    @Override
    public Optional<Account> findById(long id) {
        return rows.findById(id).map(this::detached);
    }

    @Override
    public Optional<Account> findByEmailKey(String key) {
        return rows.findByEmailKey(key).map(this::detached);
    }

    @Override
    public Optional<Account> findByUsername(String username) {
        return rows.findByUsername(username).map(this::detached);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Account> lockById(long id) {
        return rows.selectByIdForNoKeyUpdate(id).map(this::detached);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Account> lockByIdForUpdate(long id) {
        return rows.selectByIdForUpdate(id).map(this::detached);
    }

    @Override
    public boolean existsByEmailKey(String key) {
        return rows.existsByEmailKey(key);
    }

    @Override
    public boolean existsByUsername(String username) {
        return rows.existsByUsername(username);
    }

    @Override
    public Account save(Account account) {
        if (account.id() != null || account.credentialVersion() != 0)
            throw new IllegalArgumentException("Seul un nouveau compte s'enregistre");
        try {
            return detached(rows.saveAndFlush(AccountJpaEntity.from(account)));
        } catch (DataIntegrityViolationException error) {
            throw takenIdentifier(error);
        }
    }

    @Override
    public void changePassword(long id, String passwordHash) {
        rows.changePassword(id, passwordHash);
    }

    @Override
    public boolean assignUsername(long id, String username) {
        try {
            return rows.assignUsername(id, username) == 1;
        } catch (DataIntegrityViolationException error) {
            throw takenIdentifier(error);
        }
    }

    @Override
    public void recordTermsAcceptance(long id, Instant acceptedAt) {
        rows.recordTermsAcceptance(id, acceptedAt);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordSessionOpened(long id, Instant openedAt) {
        rows.recordSessionOpened(id, openedAt);
    }

    /**
     * Detaches the row: a managed entity would stay in the persistence context, and a locked read
     * that follows would return that copy rather than the row as committed.
     */
    private Account detached(AccountJpaEntity row) {
        entities.detach(row);
        return row.toDomain();
    }

    /**
     * Names the identifier that the database found taken. Any other failure is passed on as it is.
     */
    private static RuntimeException takenIdentifier(DataIntegrityViolationException error) {
        return switch (UniqueViolations.violatedConstraint(error).orElse("")) {
            case USERNAME_CONSTRAINT -> new IdentifierTakenException(Identifier.USERNAME);
            case EMAIL_CONSTRAINT -> new IdentifierTakenException(Identifier.EMAIL);
            default -> error;
        };
    }
}
