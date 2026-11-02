package fr.emploigo.auth.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

class AccountTest {
    private static Account account(boolean emailVerified, boolean enabled, String role) {
        return new Account(
                7L,
                null,
                "pseudo_du_jeune",
                "jeune@example.invalid",
                "hash",
                emailVerified,
                enabled,
                role,
                3);
    }

    @Test
    void anEnabledAccountWithAVerifiedEmailAndTheYoungPersonRoleMayAuthenticate() {
        assertThat(account(true, true, "JEUNE").mayAuthenticate()).as("may authenticate").isTrue();
    }

    static Stream<Arguments> refusals() {
        return Stream.of(
                argumentSet("compte désactivé", account(true, false, "JEUNE")),
                argumentSet("adresse non vérifiée", account(false, true, "JEUNE")),
                argumentSet("autre rôle", account(true, true, "ADMIN")));
    }

    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("refusals")
    void anAccountMayNotAuthenticateWhen(Account account) {
        assertThat(account.mayAuthenticate()).as("may authenticate").isFalse();
    }

    @Test
    void aNewAccountIsAYoungPersonWhoseEmailIsNotVerifiedUntilConfirmed() {
        assertThat(new Account("Pseudo", "jeune@example.invalid", "hash", false, true).role())
                .as("role")
                .isEqualTo("JEUNE");
        assertThat(
                        new Account("Pseudo", "jeune@example.invalid", "hash", false, true)
                                .mayAuthenticate())
                .as("may authenticate")
                .isFalse();
    }

    @Test
    void aNewAccountStoresItsUsernameInLowerCase() {
        assertThat(new Account("Pseudo", "jeune@example.invalid", "hash", false, true).username())
                .as("username")
                .isEqualTo("pseudo");
    }

    @Test
    void aConfirmedAccountMayAuthenticateAndKeepsItsKeys() {
        var account = Account.confirmed("pseudo", "jeune@example.invalid", null);

        assertThat(account.mayAuthenticate()).as("may authenticate").isTrue();
        assertThat(account.username()).as("username").isEqualTo("pseudo");
        assertThat(account.emailKey()).as("email key").isEqualTo("jeune@example.invalid");
        assertThat(account.local()).as("local").isFalse();
    }

    @Test
    void anAccountWithAPasswordHashIsLocal() {
        assertThat(account(true, true, "JEUNE").local()).as("local").isTrue();
    }

    @Test
    void aLocalAccountThatMayAuthenticateMayLogInWithAPassword() {
        assertThat(account(true, true, "JEUNE").mayLogInWithPassword()).as("may log in").isTrue();
    }

    @Test
    void anAccountWithoutAPasswordMayNotLogInWithOne() {
        assertThat(Account.confirmed("pseudo", "jeune@example.invalid", null).mayLogInWithPassword())
                .as("may log in")
                .isFalse();
    }

    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("refusals")
    void anAccountThatMayNotAuthenticateMayNotLogInWithAPassword(Account account) {
        assertThat(account.local()).as("local").isTrue();
        assertThat(account.mayLogInWithPassword()).as("may log in").isFalse();
    }

    @Test
    void stringFormKeepsTheEmailAndTheUsernameOut() {
        assertThat(account(true, true, "JEUNE").toString())
                .as("string form")
                .doesNotContain("jeune@example.invalid")
                .doesNotContain("pseudo_du_jeune");
    }
}
