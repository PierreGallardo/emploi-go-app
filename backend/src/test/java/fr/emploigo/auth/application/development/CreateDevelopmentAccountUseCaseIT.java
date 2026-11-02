package fr.emploigo.auth.application.development;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import fr.emploigo.auth.application.AuthenticationIntegrationTest;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.atomic.AtomicReference;

class CreateDevelopmentAccountUseCaseIT extends AuthenticationIntegrationTest {
    private static final String PASSWORD = "un mot de passe de développement 🔑";

    @Autowired CreateDevelopmentAccountUseCase accounts;

    @Test
    void passwordHashIsComputedOutsideATransactionAndTheAccountIsCreated() {
        var inTransaction = new AtomicReference<Boolean>();
        doAnswer(
                        call -> {
                            inTransaction.set(
                                    TransactionSynchronizationManager.isActualTransactionActive());
                            return call.callRealMethod();
                        })
                .when(passwords)
                .encode(anyString());

        accounts.create(PASSWORD);

        assertThat(inTransaction.get()).as("encoder called inside a transaction").isFalse();
        assertThat(count("auth_accounts")).as("accounts").isEqualTo(1);
    }

    @Test
    void existingAccountIsLeftAsItIsWithoutComputingAnotherHash() {
        accounts.create(PASSWORD);
        var before = jdbc.queryForObject("SELECT password_hash FROM auth_accounts", String.class);
        clearInvocations(passwords);

        accounts.create(PASSWORD);

        verify(passwords, never()).encode(anyString());
        assertThat(jdbc.queryForObject("SELECT password_hash FROM auth_accounts", String.class))
                .as("hash")
                .isEqualTo(before);
        assertThat(count("auth_accounts")).as("accounts").isEqualTo(1);
    }
}
