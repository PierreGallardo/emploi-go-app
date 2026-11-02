package fr.emploigo.auth.infrastructure.quota;

import fr.emploigo.config.ApiOnly;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;

import org.springframework.boot.data.redis.autoconfigure.DataRedisProperties;
import org.springframework.boot.data.redis.autoconfigure.LettuceClientOptionsBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * A Redis that accepts connections but does not answer is covered by the command timeout, not by
 * these options.
 */
@ApiOnly
@Configuration(proxyBeanMethods = false)
class RedisClientConfiguration {
    /** Above the 200 Tomcat request threads, so a healthy Redis never fills the queue. */
    static final int REQUEST_QUEUE_SIZE = 256;

    @Bean
    LettuceClientOptionsBuilderCustomizer authenticationRedisClientOptions(
            DataRedisProperties properties) {
        // Socket options replace those of Spring Boot, which carry the connect timeout: set it
        // again.
        var socket = SocketOptions.builder().keepAlive(true);
        if (properties.getConnectTimeout() != null)
            socket.connectTimeout(properties.getConnectTimeout());
        return builder ->
                builder.disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                        .requestQueueSize(REQUEST_QUEUE_SIZE)
                        .socketOptions(socket.build());
    }
}
