package fr.emploigo.auth.infrastructure.session;

import fr.emploigo.auth.application.session.Credentials;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

/**
 * An account that may log in with a password, as Spring Security checks it: its authorities are
 * the session's, granted after the check.
 */
record AccountUserDetails(Credentials credentials, String loginKey, String passwordHash)
        implements UserDetails {
    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of();
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return loginKey;
    }

    @Override
    public String toString() {
        return "AccountUserDetails[redacted]";
    }
}
