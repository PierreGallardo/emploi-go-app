package fr.emploigo.auth.application.development;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import fr.emploigo.auth.application.account.FakeAccountRepository;
import fr.emploigo.auth.application.password.FakePasswordEncoder;
import fr.emploigo.auth.application.password.PasswordHasher;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

class CreateDevelopmentAccountUseCaseTest {
    private static final String DEMO_EMAIL = "jeune@example.invalid";
    private static final String PASSWORD = "mot-de-passe-de-dev-1";
    private static final String OTHER_PASSWORD = "autre-mot-de-passe-dev";
    private static final String MESSAGE =
            "DEV_AUTH_PASSWORD doit contenir 20 à 128 caractères";

    private final FakeAccountRepository accounts = new FakeAccountRepository();
    private final PasswordHasher hashing =
            new PasswordHasher(
                    new FakePasswordEncoder(), new SimpleMeterRegistry(), new MockEnvironment());
    private final CreateDevelopmentAccountUseCase useCase =
            new CreateDevelopmentAccountUseCase(accounts, hashing);

    @Test
    void aValidPasswordCreatesTheDemoAccountWithAHashOfIt() {
        useCase.create(PASSWORD);

        var account = accounts.findByEmailKey(DEMO_EMAIL);
        assertThat(account).as("demo account").isPresent();
        assertThat(account.get().username()).as("username").isEqualTo("jeune_demo");
        assertThat(account.get().enabled()).as("enabled").isTrue();
        assertThat(account.get().emailVerified()).as("email verified").isTrue();
        assertThat(account.get().passwordHash()).as("stored password").isNotEqualTo(PASSWORD);
        assertThat(hashing.matches(PASSWORD, account.get().passwordHash()))
                .as("password matches the hash")
                .isTrue();
    }

    @Test
    void aPasswordOfOneHundredAndTwentyEightCharactersIsAccepted() {
        var longest = "p".repeat(128);

        useCase.create(longest);

        assertThat(accounts.findByEmailKey(DEMO_EMAIL)).as("demo account").isPresent();
    }

    @Test
    void aMissingPasswordIsRefusedAndNoAccountIsCreated() {
        assertThatThrownBy(() -> useCase.create(null))
                .as("create")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(MESSAGE);

        assertThat(accounts.findByEmailKey(DEMO_EMAIL)).as("demo account").isEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 19, 129})
    void aPasswordOutsideTheLengthRangeIsRefusedAndNoAccountIsCreated(int length) {
        var password = "p".repeat(length);

        assertThatThrownBy(() -> useCase.create(password))
                .as("create")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(MESSAGE);

        assertThat(accounts.findByEmailKey(DEMO_EMAIL)).as("demo account").isEmpty();
    }

    @Test
    void anExistingDemoAccountKeepsItsPasswordAndIsNotSavedAgain() {
        useCase.create(PASSWORD);

        assertThatCode(() -> useCase.create(OTHER_PASSWORD))
                .as("create")
                .doesNotThrowAnyException();

        var stored = accounts.findByEmailKey(DEMO_EMAIL).orElseThrow();
        assertThat(hashing.matches(PASSWORD, stored.passwordHash()))
                .as("original password kept")
                .isTrue();
        assertThat(hashing.matches(OTHER_PASSWORD, stored.passwordHash()))
                .as("other password not stored")
                .isFalse();
    }
}
