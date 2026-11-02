package fr.emploigo.auth.infrastructure.quota;

import static org.assertj.core.api.Assertions.assertThat;

import io.lettuce.core.ClientOptions;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import java.time.Duration;

/**
 * The Redis client of the API as the production configuration builds it, without connecting to a
 * server.
 */
class RedisClientConfigurationTest {
    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withInitializer(new ConfigDataApplicationContextInitializer())
                    .withInitializer(
                            context ->
                                    context.getEnvironment()
                                            .getPropertySources()
                                            .remove(
                                                    StandardEnvironment
                                                            .SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME))
                    .withPropertyValues("REDIS_HOST=localhost", "REDIS_PASSWORD=unused")
                    .withConfiguration(AutoConfigurations.of(DataRedisAutoConfiguration.class))
                    .withUserConfiguration(RedisClientConfiguration.class);

    private static ClientOptions options(AssertableApplicationContext context) {
        return context.getBean(LettuceConnectionFactory.class)
                .getClientConfiguration()
                .getClientOptions()
                .orElseThrow();
    }

    @Test
    void commandsAreBoundedToHalfASecond() {
        runner.run(
                context ->
                        assertThat(
                                        context.getBean(LettuceConnectionFactory.class)
                                                .getClientConfiguration()
                                                .getCommandTimeout())
                                .as("command timeout")
                                .isEqualTo(Duration.ofMillis(500)));
    }

    @Test
    void connectionAttemptIsBoundedToHalfASecond() {
        runner.run(
                context ->
                        assertThat(options(context).getSocketOptions().getConnectTimeout())
                                .as("connect timeout")
                                .isEqualTo(Duration.ofMillis(500)));
    }

    @Test
    void commandsAreRejectedWhileTheConnectionIsCut() {
        runner.run(
                context ->
                        assertThat(options(context).getDisconnectedBehavior())
                                .as("disconnected behavior")
                                .isEqualTo(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS));
    }

    @Test
    void theQueueOfRequestsIsBounded() {
        runner.run(
                context ->
                        assertThat(options(context).getRequestQueueSize())
                                .as("request queue size")
                                .isEqualTo(256));
    }

    @Test
    void tcpKeepAliveIsEnabled() {
        runner.run(
                context ->
                        assertThat(options(context).getSocketOptions().isKeepAlive())
                                .as("keep alive")
                                .isTrue());
    }
}
