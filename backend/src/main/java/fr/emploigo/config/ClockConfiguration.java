package fr.emploigo.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** UTC clock shared by the API and the worker; tests replace it. */
@Configuration(proxyBeanMethods = false)
@ApiAndWorker
public class ClockConfiguration {
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
