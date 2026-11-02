package fr.emploigo.auth.api.quota;

import static org.assertj.core.api.Assertions.assertThat;

import fr.emploigo.auth.application.AuthenticationIntegrationTest;
import fr.emploigo.auth.application.account.AccountRepository;
import fr.emploigo.auth.domain.Account;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

/**
 * The per-address login limit must admit a whole room sharing one public address, while still
 * counting those logins.
 */
class LoginQuotaIT extends AuthenticationIntegrationTest {
    private static final int ROOM = 25;
    private static final int WORKSHOP = 40;

    @Autowired AccountRepository accounts;

    private int login(int index, String password) throws Exception {
        var browser = newBrowser();
        return browser.post(
                        "login",
                        Map.of("identifier", "Jeune" + index, "password", password),
                        browser.csrf())
                .statusCode();
    }

    private String roomOfAccounts() {
        String password = UUID.randomUUID() + " 🔑 ";
        String hash = passwords.encode(password);
        IntStream.range(0, ROOM)
                .forEach(
                        i ->
                                accounts.save(
                                        new Account(
                                                "Jeune" + i,
                                                "jeune" + i + "@example.invalid",
                                                hash,
                                                true,
                                                true)));
        return password;
    }

    @Test
    void twentyFiveDistinctAccountsLogInFromTheSameAddressWithoutAnyRefusal() {
        String password = roomOfAccounts();

        var statuses =
                IntStream.range(0, ROOM).boxed().map(unchecked(i -> login(i, password))).toList();

        assertThat(statuses).as("statuses").hasSize(ROOM).containsOnly(200);
        // The room is one address, and every login counted against it: the limit is wide, not
        // absent.
        var counters = redis.keys("auth:limits:ip:*");
        assertThat(counters).as("address counters").hasSize(1);
        assertThat(redis.opsForValue().get(counters.iterator().next()))
                .as("attempts of the address")
                .isEqualTo(Integer.toString(ROOM));
        // The failures are forgotten by each success: the identities of the room leave no counter
        // nor delay.
        assertThat(redis.keys("auth:limits:login:*"))
                .as("counters of the failures and delays")
                .isEmpty();
    }

    @Test
    void aWorkshopOfFortyYoungPeopleWhoMistypeOnceLogsInFromTheSameAddressWithoutAnyRefusal() {
        String password = UUID.randomUUID() + " 🔑 ";
        String hash = passwords.encode(password);
        IntStream.range(0, WORKSHOP)
                .forEach(
                        i ->
                                accounts.save(
                                        new Account(
                                                "Jeune" + i,
                                                "jeune" + i + "@example.invalid",
                                                hash,
                                                true,
                                                true)));

        var statuses =
                IntStream.range(0, WORKSHOP)
                        .boxed()
                        .map(
                                unchecked(
                                        i ->
                                                List.of(
                                                        login(i, "mauvais " + password),
                                                        login(i, password))))
                        .flatMap(List::stream)
                        .toList();

        assertThat(statuses)
                .as("statuses")
                .hasSize(2 * WORKSHOP)
                .containsOnly(401, 200)
                .filteredOn(status -> status == 200)
                .hasSize(WORKSHOP);
    }
}
