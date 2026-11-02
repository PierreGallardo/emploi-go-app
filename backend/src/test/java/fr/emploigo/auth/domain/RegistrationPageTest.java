package fr.emploigo.auth.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

class RegistrationPageTest {
    private static final Instant NOW = Instant.parse("2026-03-02T10:00:00Z");
    private static final String KEY = "k".repeat(43);
    private static final String ID = "f3c1d1a2-6a55-4d5e-9b0e-2c1f2d3e4f50";

    @Test
    void issuedPageKeepsTheEmailKeyTheKeyAndTheChoiceUnderANewIdentifier() {
        var page = RegistrationPage.issued(" Lea@Gmail.com ", KEY, true, NOW);

        assertThat(page.emailKey()).as("email").isEqualTo("lea@gmail.com");
        assertThat(page.key()).as("key").isEqualTo(KEY);
        assertThat(page.remembered()).as("remembered").isTrue();
        assertThat(page.lastCode()).as("last code").isEqualTo(NOW);
        assertThat(page.id()).as("identifier").isNotBlank();
    }

    @Test
    void twoPagesForTheSameEmailGetTwoIdentifiers() {
        var first = RegistrationPage.issued("lea@gmail.com", KEY, false, NOW);

        var second = RegistrationPage.issued("lea@gmail.com", KEY, false, NOW);

        assertThat(second.id()).isNotEqualTo(first.id());
    }

    @Test
    void pageOfTextThatIsNotAnEmailIsRefused() {
        assertThatThrownBy(() -> RegistrationPage.issued("pas-une-adresse", KEY, false, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void pageIsHeldByTheTabOfItsIdentifierOnly() {
        var page = new RegistrationPage("lea@gmail.com", KEY, ID, true, NOW);

        assertThat(page.heldBy(ID, NOW)).as("same identifier").isTrue();
        assertThat(page.heldBy("autre", NOW)).as("other identifier").isFalse();
        assertThat(page.heldBy(null, NOW)).as("no identifier").isFalse();
    }

    @Test
    void pageIsHeldTwentyFourHoursAfterItsLastCode() {
        var page = new RegistrationPage("lea@gmail.com", KEY, ID, true, NOW);

        assertThat(page.heldBy(ID, NOW.plus(Duration.ofHours(24)).minusMillis(1)))
                .as("just before")
                .isTrue();
        assertThat(page.heldBy(ID, NOW.plus(Duration.ofHours(24)))).as("at the end").isFalse();
    }

    @Test
    void renewedPageKeepsItsKeyIdentifierAndChoiceAndDatesItsNewCode() {
        var page = new RegistrationPage("lea@gmail.com", KEY, ID, true, NOW);

        assertThat(page.renewed(NOW.plusSeconds(60)))
                .isEqualTo(
                        new RegistrationPage("lea@gmail.com", KEY, ID, true, NOW.plusSeconds(60)));
    }

    @Test
    void textOfThePageHidesTheKeyAndTheEmail() {
        var page = new RegistrationPage("lea@gmail.com", KEY, ID, true, NOW);

        assertThat(page.toString()).doesNotContain(KEY).doesNotContain("lea");
    }
}
