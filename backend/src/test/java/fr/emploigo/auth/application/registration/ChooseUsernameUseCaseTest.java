package fr.emploigo.auth.application.registration;

import static fr.emploigo.auth.application.session.SessionPersistence.REMEMBERED;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import fr.emploigo.auth.application.account.FakeAccountRepository;
import fr.emploigo.auth.application.failure.AuthenticationFailedException;
import fr.emploigo.auth.application.session.AuthenticatedAccount;
import fr.emploigo.auth.application.session.AuthenticationGrant;
import fr.emploigo.auth.application.session.AuthenticationSessions;
import fr.emploigo.auth.application.session.Credentials;
import fr.emploigo.auth.domain.Account;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.UUID;

class ChooseUsernameUseCaseTest {
    private static final UUID FAMILY = UUID.randomUUID();
    private static final String COOKIE = FAMILY + "." + "s".repeat(43);
    private final FakeAccountRepository accounts = new FakeAccountRepository();
    private final AuthenticationSessions sessions = mock(AuthenticationSessions.class);
    private final ChooseUsernameUseCase useCase = new ChooseUsernameUseCase(accounts, sessions);

    private long accountWithoutUsername(String email) {
        return accounts.save(Account.withoutUsername(email, "empreinte")).id();
    }

    private static RegistrationFailedException.Reason reasonOf(Throwable error) {
        return ((RegistrationFailedException) error).reason();
    }

    @Test
    void chosenUsernameIsGivenToTheAccountAndOpensANewGrantKeptLikeTheSession() {
        long id = accountWithoutUsername("jeune@example.invalid");
        var grant =
                new AuthenticationGrant(
                        new AuthenticatedAccount(id, null, "Jeune", 0, FAMILY), "secret");
        when(sessions.create(new Credentials(id, 0), REMEMBERED, COOKIE, FAMILY))
                .thenReturn(grant);

        var result = useCase.choose(id, 0, FAMILY, " Jeune ", COOKIE);

        assertThat(result).as("grant").isSameAs(grant);
        assertThat(accounts.findById(id).orElseThrow().username())
                .as("username")
                .isEqualTo("jeune");
    }

    @Test
    void usernameOfAnotherAccountIsRefusedAsUnavailable() {
        accounts.save(new Account("Jeune", "autre@example.invalid", "empreinte", true, true));
        long id = accountWithoutUsername("jeune@example.invalid");

        assertThatThrownBy(() -> useCase.choose(id, 0, FAMILY, "JEUNE", null))
                .as("choose")
                .extracting(ChooseUsernameUseCaseTest::reasonOf)
                .isEqualTo(RegistrationFailedException.Reason.USERNAME_UNAVAILABLE);
        assertThat(accounts.findById(id).orElseThrow().hasUsername())
                .as("account has a username")
                .isFalse();
    }

    @Test
    void accountThatHasAUsernameIsRefusedAsAlreadyChosen() {
        long id =
                accounts.save(new Account("Jeune", "jeune@example.invalid", "empreinte", true, true))
                        .id();

        assertThatThrownBy(() -> useCase.choose(id, 0, FAMILY, "Autre", null))
                .as("choose")
                .extracting(ChooseUsernameUseCaseTest::reasonOf)
                .isEqualTo(RegistrationFailedException.Reason.USERNAME_ALREADY_CHOSEN);
        assertThat(accounts.findById(id).orElseThrow().username())
                .as("username")
                .isEqualTo("jeune");
    }

    @Test
    void refusedSessionGivesNoUsername() {
        long id = accountWithoutUsername("jeune@example.invalid");
        when(sessions.verify(id, 0, FAMILY))
                .thenThrow(AuthenticationFailedException.unauthenticated());

        assertThatThrownBy(() -> useCase.choose(id, 0, FAMILY, "Jeune", null))
                .as("choose")
                .isInstanceOf(AuthenticationFailedException.class);
        assertThat(accounts.findById(id).orElseThrow().hasUsername())
                .as("account has a username")
                .isFalse();
    }

    @ParameterizedTest(name = "« {0} »")
    @ValueSource(strings = {" ", "jeune@exemple", "a\u0007b", "léa", "jeune-demo"})
    void invalidUsernameIsRefusedBeforeAnyRead(String username) {
        long id = accountWithoutUsername("jeune@example.invalid");

        assertThatThrownBy(() -> useCase.choose(id, 0, FAMILY, username, null))
                .as("choose")
                .extracting(ChooseUsernameUseCaseTest::reasonOf)
                .isEqualTo(RegistrationFailedException.Reason.INVALID_USERNAME);
        verifyNoInteractions(sessions);
        assertThat(accounts.findById(id).orElseThrow().hasUsername())
                .as("account has a username")
                .isFalse();
    }
}
