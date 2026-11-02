package fr.emploigo.auth.application.account;

import fr.emploigo.auth.domain.Account;
import fr.emploigo.auth.domain.IdentifierTakenException;
import fr.emploigo.auth.domain.IdentifierTakenException.Identifier;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Accounts in memory, with the uniqueness of the keys. The locks change nothing. */
public final class FakeAccountRepository implements AccountRepository {
    private final Map<Long, Account> accounts = new HashMap<>();
    private final Map<Long, Instant> termsAcceptances = new HashMap<>();
    private long lastId;
    private Identifier conflictOnNextSave;

    /**
     * Makes the next save refuse {@code identifier} as if another transaction had committed it
     * after the checks of the use case: nothing is stored.
     */
    public void conflictOnNextSave(Identifier identifier) {
        conflictOnNextSave = identifier;
    }

    /** Stores an account that already has its id, as a fixture. */
    public void store(Account account) {
        accounts.put(account.id(), account);
    }

    @Override
    public Optional<Account> findById(long id) {
        return Optional.ofNullable(accounts.get(id));
    }

    @Override
    public Optional<Account> findByEmailKey(String key) {
        return accounts.values().stream()
                .filter(account -> account.emailKey().equals(key))
                .findFirst();
    }

    @Override
    public Optional<Account> findByUsername(String username) {
        return accounts.values().stream()
                .filter(account -> username.equals(account.username()))
                .findFirst();
    }

    @Override
    public Optional<Account> lockById(long id) {
        return findById(id);
    }

    @Override
    public Optional<Account> lockByIdForUpdate(long id) {
        return findById(id);
    }

    @Override
    public boolean existsByEmailKey(String key) {
        return findByEmailKey(key).isPresent();
    }

    @Override
    public boolean existsByUsername(String username) {
        return findByUsername(username).isPresent();
    }

    @Override
    public Account save(Account account) {
        if (account.id() != null || account.credentialVersion() != 0)
            throw new IllegalArgumentException("Seul un nouveau compte s'enregistre");
        if (conflictOnNextSave != null) {
            var identifier = conflictOnNextSave;
            conflictOnNextSave = null;
            throw new IdentifierTakenException(identifier);
        }
        if (existsByEmailKey(account.emailKey()))
            throw new IdentifierTakenException(Identifier.EMAIL);
        if (account.hasUsername() && existsByUsername(account.username()))
            throw new IdentifierTakenException(Identifier.USERNAME);
        var saved =
                new Account(
                        ++lastId,
                        // As the default of the column.
                        UUID.randomUUID(),
                        account.username(),
                        account.emailKey(),
                        account.passwordHash(),
                        account.emailVerified(),
                        account.enabled(),
                        account.role(),
                        0);
        store(saved);
        return saved;
    }

    @Override
    public void changePassword(long id, String passwordHash) {
        findById(id)
                .ifPresent(
                        account ->
                                store(
                                        new Account(
                                                account.id(),
                                                account.publicId(),
                                                account.username(),
                                                account.emailKey(),
                                                passwordHash,
                                                account.emailVerified(),
                                                account.enabled(),
                                                account.role(),
                                                account.credentialVersion() + 1)));
    }

    @Override
    public boolean assignUsername(long id, String username) {
        var account = findById(id).filter(value -> !value.hasUsername()).orElse(null);
        if (account == null) return false;
        if (existsByUsername(username)) throw new IdentifierTakenException(Identifier.USERNAME);
        store(
                new Account(
                        account.id(),
                        account.publicId(),
                        username,
                        account.emailKey(),
                        account.passwordHash(),
                        account.emailVerified(),
                        account.enabled(),
                        account.role(),
                        account.credentialVersion()));
        return true;
    }

    @Override
    public void recordTermsAcceptance(long id, Instant acceptedAt) {
        termsAcceptances.put(id, acceptedAt);
    }

    @Override
    public void recordSessionOpened(long id, Instant openedAt) {}

    /** When the account accepted the terms of use, or {@code null}. */
    public Instant termsAcceptedAt(long id) {
        return termsAcceptances.get(id);
    }
}
