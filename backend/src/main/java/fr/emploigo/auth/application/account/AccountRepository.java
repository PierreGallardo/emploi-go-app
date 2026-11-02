package fr.emploigo.auth.application.account;

import fr.emploigo.auth.domain.Account;

import java.time.Instant;
import java.util.Optional;

/**
 * The only access to the accounts. Every read is a fresh copy from the database, never a cached
 * entity, so that a row read again after a lock is the committed one. The locks run in the
 * transaction of the use case.
 */
public interface AccountRepository {
    Optional<Account> findById(long id);

    Optional<Account> findByEmailKey(String key);

    Optional<Account> findByUsername(String username);

    /** Takes the row lock of a session: it does not block the foreign keys that point to it. */
    Optional<Account> lockById(long id);

    /** Takes the row lock of a credential change: it also blocks the foreign keys. */
    Optional<Account> lockByIdForUpdate(long id);

    boolean existsByEmailKey(String key);

    boolean existsByUsername(String username);

    /** Inserts a new account: it has no id yet, and its version starts at zero. */
    Account save(Account account);

    void changePassword(long id, String passwordHash);

    /**
     * Gives its username to an account that has none. Returns whether it had none; throws {@link
     * fr.emploigo.auth.domain.IdentifierTakenException} when another account holds it.
     */
    boolean assignUsername(long id, String username);

    /** The account was created by a form that required the terms of use. */
    void recordTermsAcceptance(long id, Instant acceptedAt);

    /** A session was opened for the account, under its lock. */
    void recordSessionOpened(long id, Instant openedAt);
}
