package fr.emploigo.support;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.properties.CanBeAnnotated;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;

import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

import java.lang.annotation.Annotation;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * The classes and bean methods of the application that a context of the other role must not load,
 * read from the compiled code: those annotated with a role, and the Spring components of an {@code
 * api} package, annotated or not.
 */
public final class ReservedComponents {
    private static final JavaClasses PRODUCTION_CLASSES =
            new ClassFileImporter()
                    .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                    .importPackages("fr.emploigo");

    private ReservedComponents() {}

    public static Stream<Class<?>> annotatedWith(Class<? extends Annotation> role) {
        return sorted(
                PRODUCTION_CLASSES.that(CanBeAnnotated.Predicates.annotatedWith(role)),
                "classes annotated with " + role.getSimpleName());
    }

    public static Stream<Class<?>> componentsInApiPackages() {
        return sorted(
                PRODUCTION_CLASSES
                        .that(JavaClass.Predicates.resideInAPackage("fr.emploigo.*.api.."))
                        .that(
                                CanBeAnnotated.Predicates.annotatedWith(Component.class)
                                        .or(
                                                CanBeAnnotated.Predicates.metaAnnotatedWith(
                                                        Component.class))),
                "Spring components of the api packages");
    }

    /** The {@code Class#method} of the bean methods annotated with a role. */
    public static Stream<String> methodsAnnotatedWith(Class<? extends Annotation> role) {
        var methods =
                PRODUCTION_CLASSES.stream()
                        .flatMap(javaClass -> javaClass.getMethods().stream())
                        .filter(method -> method.isAnnotatedWith(Bean.class))
                        .filter(method -> method.isAnnotatedWith(role))
                        .map(ReservedComponents::name)
                        .sorted()
                        .toList();
        if (methods.isEmpty()) {
            throw new IllegalStateException(
                    "No method annotated with "
                            + role.getSimpleName()
                            + ": the test would check nothing");
        }
        return methods.stream();
    }

    private static String name(JavaMethod method) {
        return method.getOwner().getName() + "#" + method.getName();
    }

    private static Stream<Class<?>> sorted(JavaClasses classes, String description) {
        if (classes.isEmpty()) {
            throw new IllegalStateException(
                    "No production class found for the "
                            + description
                            + ": the test would check nothing");
        }
        return classes.stream()
                .sorted(Comparator.comparing(JavaClass::getName))
                .map(javaClass -> (Class<?>) javaClass.reflect());
    }
}
