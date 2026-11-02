package fr.emploigo.auth.infrastructure.session;

import fr.emploigo.auth.application.account.AccountRepository;
import fr.emploigo.auth.application.session.Credentials;
import fr.emploigo.auth.domain.Account;
import fr.emploigo.auth.domain.AccountIdentifier;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.util.Optional;

/**
 * Finds the account of a typed identifier, by email or by username. An account that may not log
 * in is not found, never disabled or locked: Spring Security then answers as for an unknown
 * account, after comparing the password to a dummy hash.
 */
final class AccountUserDetailsLookup implements UserDetailsService {
    private final AccountRepository accounts;

    AccountUserDetailsLookup(AccountRepository accounts) {
        this.accounts = accounts;
    }

    @Override
    public UserDetails loadUserByUsername(String identifier) {
        return AccountIdentifier.readableLoginKey(identifier)
                .flatMap(key -> find(key).map(account -> details(key, account)))
                .orElseThrow(
                        () -> new UsernameNotFoundException("Aucun compte ne peut se connecter."));
    }

    private Optional<Account> find(String key) {
        var account =
                key.contains("@") ? accounts.findByEmailKey(key) : accounts.findByUsername(key);
        return account.filter(Account::mayLogInWithPassword);
    }

    private static UserDetails details(String key, Account account) {
        return new AccountUserDetails(
                new Credentials(account.id(), account.credentialVersion()),
                key,
                account.passwordHash());
    }
}
