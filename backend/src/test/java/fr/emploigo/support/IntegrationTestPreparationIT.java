package fr.emploigo.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import fr.emploigo.auth.application.AuthenticationTestSupport;

import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;

import java.time.Duration;
import java.util.Set;

class IntegrationTestPreparationIT extends IntegrationTest {
    private static final String LEFTOVER = "leftover";

    @Test
    void preparationWaitsForARedisThatStalls() {
        redis.opsForValue().set(LEFTOVER, "1");
        Containers.stallRedis(Duration.ofSeconds(2));
        awaitStall();

        resetEnvironment();

        assertThat(redis.keys("*"))
                .as("keys of Redis")
                .isEqualTo(Set.of(AuthenticationTestSupport.WITNESS_KEY));
    }

    /**
     * The client gives up on a command after its delay: Redis is then known to be stalled, not
     * about to be.
     */
    private void awaitStall() {
        await().atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(10))
                .until(
                        () -> {
                            try {
                                redis.hasKey("stall-probe");
                                return false;
                            } catch (QueryTimeoutException stalled) {
                                return true;
                            }
                        });
    }
}
