package fr.emploigo.config;

import org.springframework.context.annotation.Conditional;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Reserves a component to the worker: the API does not load it.
 *
 * <p>On a class that {@code @EnableConfigurationProperties} registers, it only records the role: the
 * condition is that of the configuration which enables the class.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Conditional(ApplicationRole.OnWorker.class)
public @interface WorkerOnly {}
