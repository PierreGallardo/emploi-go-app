package fr.emploigo.auth.application;

import fr.emploigo.auth.application.quota.AuthenticationRateLimiter;
import fr.emploigo.auth.application.quota.LoginAttemptLimiter;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Observes the authentication beans of the integration context. Declared once on the shared base,
 * so every test class uses the same context.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@MockitoSpyBean(
        types = {
            PasswordEncoder.class,
            AuthenticationRateLimiter.class,
            LoginAttemptLimiter.class
        })
public @interface AuthenticationTestDoubles {}
