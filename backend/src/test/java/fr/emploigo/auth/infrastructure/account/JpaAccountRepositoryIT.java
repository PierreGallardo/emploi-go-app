package fr.emploigo.auth.infrastructure.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import fr.emploigo.auth.application.AuthenticationIntegrationTest;
import fr.emploigo.auth.application.account.AccountRepository;
import fr.emploigo.auth.application.session.ReconnectionRepository;
import fr.emploigo.auth.domain.Account;
import fr.emploigo.auth.domain.IdentifierTakenException;
import fr.emploigo.auth.domain.IdentifierTakenException.Identifier;
import fr.emploigo.support.RowLock;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import javax.sql.DataSource;

class JpaAccountRepositoryIT extends AuthenticationIntegrationTest {
    @Autowired AccountRepository accounts;
    @Autowired ReconnectionRepository reconnections;
    @Autowired DataSource dataSource;
    @Autowired TransactionTemplate transaction;

    private Account saved() {
        return accounts.save(new Account("Jeune", "jeune@example.invalid", "hash", true, true));
    }

    @Test
    void savedAccountIsFoundByItsIdAndItsKeys() {
        var account = saved();

        assertThat(account.id()).as("id").isNotNull();
        assertThat(account.role()).as("role").isEqualTo("JEUNE");
        assertThat(account.credentialVersion()).as("version").isZero();
        assertThat(accounts.findById(account.id())).as("by id").contains(account);
        assertThat(accounts.findByEmailKey("jeune@example.invalid"))
                .as("by email")
                .contains(account);
        assertThat(accounts.findByUsername("jeune")).as("by username").contains(account);
        assertThat(accounts.existsByEmailKey("jeune@example.invalid")).as("email").isTrue();
        assertThat(accounts.existsByUsername("jeune")).as("username").isTrue();
    }

    @Test
    void anAccountKeepsTheDateAtWhichItAcceptedTheTerms() {
        var account = saved();
        var acceptedAt = Instant.parse("2026-10-17T10:00:00Z");

        accounts.recordTermsAcceptance(account.id(), acceptedAt);

        assertThat(
                        jdbc.queryForObject(
                                "SELECT terms_accepted_at FROM auth_accounts WHERE id = ?",
                                Instant.class,
                                account.id()))
                .as("terms accepted at")
                .isEqualTo(acceptedAt);
    }

    @Test
    void anUnknownAccountIsAbsent() {
        assertThat(accounts.findById(1L)).as("by id").isEmpty();
        assertThat(accounts.findByEmailKey("autre@example.invalid")).as("by email").isEmpty();
        assertThat(accounts.existsByEmailKey("autre@example.invalid")).as("email").isFalse();
        assertThat(accounts.existsByUsername("autre")).as("username").isFalse();
    }

    @Test
    void aSecondAccountWithTheSameEmailIsRefusedAsEmailTaken() {
        saved();

        assertThatThrownBy(
                        () ->
                                accounts.save(
                                        new Account(
                                                "Autre",
                                                "jeune@example.invalid",
                                                "hash",
                                                true,
                                                true)))
                .as("second account")
                .isInstanceOfSatisfying(
                        IdentifierTakenException.class,
                        error -> assertThat(error.identifier()).isEqualTo(Identifier.EMAIL));
    }

    @Test
    void aSecondAccountWithTheSameUsernameInAnotherCaseIsRefusedAsUsernameTaken() {
        saved();

        assertThatThrownBy(
                        () ->
                                accounts.save(
                                        new Account(
                                                "JEUNE",
                                                "autre@example.invalid",
                                                "hash",
                                                true,
                                                true)))
                .as("second account")
                .isInstanceOfSatisfying(
                        IdentifierTakenException.class,
                        error -> assertThat(error.identifier()).isEqualTo(Identifier.USERNAME));
    }

    @Test
    void theDatabaseRefusesAUsernameInUpperCase() {
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        """
                                        INSERT INTO auth_accounts(
                                            username, email_key, email_verified, enabled)
                                        VALUES ('Jeune', 'jeune@example.invalid', TRUE, TRUE)
                                        """))
                .as("insert in upper case")
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("auth_accounts_username_lower_check");
    }

    @Test
    void changingThePasswordIncrementsTheCredentialVersion() {
        var account = saved();

        accounts.changePassword(account.id(), "nouvelle-empreinte");

        var changed = accounts.findById(account.id()).orElseThrow();
        assertThat(changed.passwordHash()).as("hash").isEqualTo("nouvelle-empreinte");
        assertThat(changed.credentialVersion()).as("version").isEqualTo(1);
    }

    @Test
    void aLockedReadAfterAPlainReadInTheSameTransactionSeesTheRowAsItIs() {
        var account = saved();

        var version =
                transaction.execute(
                        status -> {
                            accounts.findById(account.id());
                            jdbc.update(
                                    "UPDATE auth_accounts SET credential_version = 5 WHERE id = "
                                            + account.id());
                            return accounts.lockByIdForUpdate(account.id())
                                    .orElseThrow()
                                    .credentialVersion();
                        });

        assertThat(version).as("version read after the lock").isEqualTo(5);
    }

    @Test
    void aReadAfterTheLockSeesTheCommittedRow() throws Exception {
        var account = saved();

        try (var lock = RowLock.hold(dataSource, "auth_accounts", account.id())) {
            var waiting =
                    CompletableFuture.supplyAsync(
                            () ->
                                    transaction.execute(
                                            status -> {
                                                var locked =
                                                        accounts.lockByIdForUpdate(account.id())
                                                                .orElseThrow();
                                                return locked.credentialVersion();
                                            }));
            RowLock.awaitWaiters(jdbc, 1, Duration.ofSeconds(10));
            lock.execute(
                    "UPDATE auth_accounts SET credential_version = 5 WHERE id = " + account.id());
            lock.commit();

            assertThat(waiting.get(10, TimeUnit.SECONDS))
                    .as("version read after the lock")
                    .isEqualTo(5);
        }
    }

    @Test
    void theSessionLockLetsAReconnectionBeInsertedForTheAccount() throws Exception {
        var account = saved();
        try (var held = new Held(() -> accounts.lockById(account.id()))) {
            insert(account).get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void theSessionLockMakesAnotherLockWait() throws Exception {
        var account = saved();
        try (var held = new Held(() -> accounts.lockById(account.id()))) {
            var other =
                    CompletableFuture.runAsync(
                            () ->
                                    transaction.executeWithoutResult(
                                            status -> accounts.lockById(account.id())));

            RowLock.awaitWaiters(jdbc, 1, Duration.ofSeconds(10));
            assertThat(other).as("second lock").isNotDone();
            held.close();
            other.get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void aLockOutsideATransactionIsRefused() {
        assertThatThrownBy(() -> accounts.lockById(1L))
                .as("lock without transaction")
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    static Stream<Arguments> accountsThatAreNotNew() {
        return Stream.of(
                argumentSet(
                        "compte avec une version",
                        new Account(
                                null,
                                null,
                                "jeune",
                                "jeune@example.invalid",
                                "hash",
                                true,
                                true,
                                "JEUNE",
                                2)),
                argumentSet(
                        "compte déjà enregistré",
                        new Account(
                                1L,
                                null,
                                "jeune",
                                "jeune@example.invalid",
                                "hash",
                                true,
                                true,
                                "JEUNE",
                                0)));
    }

    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("accountsThatAreNotNew")
    void accountThatIsNotNewIsRefusedOnSave(Account account) {
        assertThatThrownBy(() -> accounts.save(account))
                .as("save")
                .isInstanceOf(InvalidDataAccessApiUsageException.class)
                .hasMessageContaining("nouveau compte");
    }

    @Test
    void theCredentialLockMakesAReconnectionInsertWait() throws Exception {
        var account = saved();
        try (var held = new Held(() -> accounts.lockByIdForUpdate(account.id()))) {
            var insertion = insert(account);

            RowLock.awaitWaiters(jdbc, 1, Duration.ofSeconds(10));
            assertThat(insertion).as("insertion while locked").isNotDone();
            held.close();
            insertion.get(10, TimeUnit.SECONDS);
        }
    }

    private CompletableFuture<Void> insert(Account account) {
        return CompletableFuture.runAsync(
                () ->
                        reconnections.insert(
                                UUID.randomUUID(),
                                account.id(),
                                0,
                                "h".repeat(64),
                                clock.instant().plus(Duration.ofDays(1)),
                                clock.instant()));
    }

    /** A transaction that has taken a lock and keeps it until released. */
    private final class Held implements AutoCloseable {
        private final CountDownLatch release = new CountDownLatch(1);
        private final CompletableFuture<Void> transactionEnd;

        Held(Runnable lock) throws Exception {
            var locked = new CountDownLatch(1);
            transactionEnd =
                    CompletableFuture.runAsync(
                            () ->
                                    transaction.executeWithoutResult(
                                            status -> {
                                                try {
                                                    lock.run();
                                                } finally {
                                                    locked.countDown();
                                                }
                                                await();
                                            }));
            boolean taken = locked.await(10, TimeUnit.SECONDS);
            if (!taken || transactionEnd.isCompletedExceptionally()) {
                release.countDown();
                transactionEnd.get(10, TimeUnit.SECONDS);
            }
            assertThat(taken).as("lock taken").isTrue();
        }

        private void await() {
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException interruption) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public void close() throws Exception {
            release.countDown();
            transactionEnd.get(10, TimeUnit.SECONDS);
        }
    }
}
