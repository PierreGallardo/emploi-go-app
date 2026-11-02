package fr.emploigo.auth.application.session;

import fr.emploigo.auth.application.account.AccountRepository;

import java.time.Clock;

/**
 * Builds the sessions with their real revoker, for the unit tests of the other packages of {@code
 * auth}: the revoker is package-private.
 */
public final class AuthenticationSessionsFixture {
    private AuthenticationSessionsFixture() {}

    public static AuthenticationSessions of(
            AccountRepository accounts,
            ReconnectionRepository reconnections,
            Clock clock,
            BrowserProofRepository proofs) {
        var revoker =
                new ReconnectionRevoker(
                        reconnections,
                        new ReconnectionTransactions(accounts, reconnections, clock));
        return new AuthenticationSessions(accounts, reconnections, revoker, clock, proofs);
    }
}
