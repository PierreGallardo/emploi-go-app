package fr.emploigo.config;

import org.springframework.context.annotation.Conditional;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Reserves a component to the API: the worker does not load it. It combines with any other
 * condition, a {@code @Profile} included, which a {@code @Profile} used as meta-annotation would
 * not (it would be an "or").
 *
 * <p>On a class that {@code @EnableConfigurationProperties} registers, it only records the role: the
 * condition is that of the configuration which enables the class.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Conditional(ApplicationRole.OnApi.class)
public @interface ApiOnly {}
