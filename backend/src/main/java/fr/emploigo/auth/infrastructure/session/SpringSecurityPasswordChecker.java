package fr.emploigo.auth.infrastructure.session;

import fr.emploigo.auth.application.account.AccountRepository;
import fr.emploigo.auth.application.password.PasswordHasher;
import fr.emploigo.auth.application.session.Credentials;
import fr.emploigo.auth.application.session.PasswordChecker;
import fr.emploigo.config.ApiOnly;

import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * The password check of a {@link DaoAuthenticationProvider}. The manager is no bean, and nor is its
 * user service: Spring Security would otherwise build a second global provider on the encoder bean,
 * outside the limit of simultaneous computations.
 */
@Component
@ApiOnly
public class SpringSecurityPasswordChecker implements PasswordChecker {
    private final AuthenticationManager manager;

    public SpringSecurityPasswordChecker(AccountRepository accounts, PasswordHasher hashing) {
        var provider = new DaoAuthenticationProvider(new AccountUserDetailsLookup(accounts));
        provider.setPasswordEncoder(new CappedPasswordEncoder(hashing));
        this.manager = new ProviderManager(provider);
    }

    @Override
    public Optional<Credentials> check(String identifier, String password) {
        try {
            var result =
                    manager.authenticate(
                            UsernamePasswordAuthenticationToken.unauthenticated(
                                    identifier, password));
            return Optional.of(((AccountUserDetails) result.getPrincipal()).credentials());
        } catch (InternalAuthenticationServiceException error) {
            // Spring wraps a failure of the account read: it reaches the caller as it was thrown.
            if (error.getCause() instanceof RuntimeException cause) throw cause;
            throw error;
        } catch (AuthenticationException error) {
            return Optional.empty();
        }
    }
}
