package fr.emploigo.auth.infrastructure.google;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import fr.emploigo.auth.application.AuthenticationIntegrationTest;
import fr.emploigo.auth.application.account.AccountRepository;
import fr.emploigo.auth.application.google.GoogleRepository;
import fr.emploigo.auth.domain.Account;
import fr.emploigo.auth.domain.AuthenticationSecrets;
import fr.emploigo.auth.domain.GoogleIdentity;
import fr.emploigo.auth.domain.IdentifierTakenException;
import fr.emploigo.auth.domain.IdentifierTakenException.Identifier;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.UUID;

class JpaGoogleRepositoryIT extends AuthenticationIntegrationTest {
    private static final String SUBJECT = "sujet-verification";
    private static final String EMAIL = "verification@example.invalid";

    @Autowired GoogleRepository google;
    @Autowired AccountRepository accounts;
    @Autowired TransactionTemplate transaction;

    @Test
    void aSecondLinkOfTheSameSubjectIsRefusedAsGoogleSubjectTaken() {
        long first = account("premier_jeune", "premier@example.invalid").id();
        long second = account("second_jeune", "second@example.invalid").id();
        link("sujet-partage", first);

        assertThatThrownBy(() -> link("sujet-partage", second))
                .as("second link")
                .isInstanceOfSatisfying(
                        IdentifierTakenException.class,
                        error ->
                                assertThat(error.identifier())
                                        .isEqualTo(Identifier.GOOGLE_SUBJECT));
    }

    @Test
    void aSecondLinkOfTheSameAccountIsRefusedAsGoogleAccountTaken() {
        long owner = account("jeune_lie", "lie@example.invalid").id();
        link("premier-sujet", owner);

        assertThatThrownBy(() -> link("second-sujet", owner))
                .as("second link")
                .isInstanceOfSatisfying(
                        IdentifierTakenException.class,
                        error ->
                                assertThat(error.identifier())
                                        .isEqualTo(Identifier.GOOGLE_ACCOUNT));
    }

    @Test
    void confirmingAPendingVerificationUsesItOnce() {
        UUID id = UUID.randomUUID();
        prepare(id, clock.instant().plusSeconds(3600));

        assertThat(confirm(id)).as("first confirmation").isTrue();
        assertThat(confirm(id)).as("second confirmation").isFalse();
        assertThat(google.verification(id).status()).as("status").isEqualTo("USED");
    }

    @Test
    void confirmingAReplacedVerificationLeavesItReplaced() {
        UUID replaced = UUID.randomUUID();
        prepare(replaced, clock.instant().plusSeconds(3600));
        prepare(UUID.randomUUID(), clock.instant().plusSeconds(3600));

        assertThat(confirm(replaced)).as("confirmation of the replaced link").isFalse();
        assertThat(google.verification(replaced).status()).as("status").isEqualTo("REPLACED");
    }

    @Test
    void confirmingAnExpiredVerificationLeavesItPending() {
        UUID id = UUID.randomUUID();
        prepare(id, clock.instant().minusSeconds(1));

        assertThat(confirm(id)).as("confirmation of the expired link").isFalse();
        assertThat(google.verification(id).status()).as("status").isEqualTo("PENDING");
    }

    private void prepare(UUID id, Instant expiry) {
        transaction.executeWithoutResult(
                status ->
                        google.prepareVerification(
                                id,
                                new GoogleIdentity(SUBJECT, EMAIL, false),
                                AuthenticationSecrets.hash("jeton-" + id),
                                "cle-test",
                                expiry));
    }

    private boolean confirm(UUID id) {
        return transaction.execute(status -> google.confirm(id, clock.instant()));
    }

    private Account account(String username, String email) {
        return accounts.save(new Account(username, email, "hash", true, true));
    }

    private void link(String subject, long accountId) {
        transaction.executeWithoutResult(status -> google.link(subject, accountId));
    }
}
