package fr.emploigo.auth.application.development;

import fr.emploigo.auth.application.account.AccountRepository;
import fr.emploigo.auth.application.password.PasswordHasher;
import fr.emploigo.auth.domain.Account;
import fr.emploigo.config.ApiOnly;

import org.springframework.stereotype.Service;

@ApiOnly
@Service
public class CreateDevelopmentAccountUseCase {
    private final AccountRepository accounts;
    private final PasswordHasher hashing;

    public CreateDevelopmentAccountUseCase(AccountRepository accounts, PasswordHasher hashing) {
        this.accounts = accounts;
        this.hashing = hashing;
    }

    /**
     * Not transactional: the hash is a costly computation, and the repository saves with a single
     * statement.
     */
    public void create(String password) {
        if (password == null || password.length() < 20 || password.length() > 128)
            throw new IllegalArgumentException(
                    "DEV_AUTH_PASSWORD doit contenir 20 à 128 caractères");
        if (accounts.findByEmailKey("jeune@example.invalid").isEmpty()) {
            accounts.save(
                    new Account(
                            "jeune_demo",
                            "jeune@example.invalid",
                            hashing.encode(password),
                            true,
                            true));
        }
    }
}
