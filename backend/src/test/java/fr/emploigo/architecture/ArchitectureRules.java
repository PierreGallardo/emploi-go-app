package fr.emploigo.architecture;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.assignableTo;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.AccessTarget;
import com.tngtech.archunit.core.domain.JavaCall;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.CompositeArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.library.dependencies.SliceAssignment;
import com.tngtech.archunit.library.dependencies.SliceIdentifier;

import fr.emploigo.config.ApiAndWorker;
import fr.emploigo.config.ApiOnly;
import fr.emploigo.config.WorkerOnly;

import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;

import jakarta.persistence.Entity;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.repository.Repository;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RestController;

import java.lang.annotation.Annotation;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Stream;

final class ArchitectureRules {
    static final List<String> LAYERS =
            List.of("api", "application", "domain", "infrastructure");
    private static final String CONSTRUCTOR_NAME = "<init>";
    private static final List<String> HTTP_PACKAGES =
            List.of(
                    "org.springframework.web",
                    "org.springframework.http",
                    "jakarta.servlet",
                    "javax.servlet",
                    "java.net.http",
                    "io.swagger",
                    "org.springdoc",
                    "com.fasterxml.jackson",
                    "tools.jackson");
    private static final List<String> PERSISTENCE_PACKAGES =
            List.of(
                    "jakarta.persistence",
                    "javax.persistence",
                    "java.sql",
                    "javax.sql",
                    "org.springframework.data",
                    "org.springframework.jdbc",
                    "org.hibernate",
                    "org.jooq",
                    "org.postgresql",
                    "org.flywaydb");
    private static final List<String> SDK_PACKAGES =
            List.of(
                    "com.resend",
                    "com.github.kagkarlsson",
                    "org.springframework.data.redis",
                    "io.lettuce");

    private static final List<String> JPA_PACKAGES =
            List.of("jakarta.persistence", "javax.persistence", "org.hibernate");
    private static final String SPRING_DATA_PACKAGE = "org.springframework.data";
    private static final List<String> FORBIDDEN_NAME_WORDS =
            List.of("Utils", "Helper", "Manager", "Impl", "Data", "Info", "Service");
    private static final String SPRING_DATA_PREFIX = "SpringData";

    private static final List<Class<? extends Annotation>> ROLES =
            List.of(ApiOnly.class, WorkerOnly.class, ApiAndWorker.class);

    private ArchitectureRules() {}

    static List<ArchRule> all(String rootPackage) {
        return List.of(
                packagePlacement(rootPackage),
                apiBoundaries(rootPackage),
                applicationBoundaries(rootPackage),
                domainBoundaries(rootPackage),
                infrastructureBoundaries(rootPackage),
                configurationBoundaries(rootPackage),
                moduleBoundaries(rootPackage),
                moduleCycles(rootPackage),
                subjectsOfADomainAreFreeOfCycles(rootPackage),
                domainIsFreeOfPersistence(rootPackage),
                applicationIsFreeOfInfrastructureSpringDataOrJpa(rootPackage),
                jpaEntitiesAreJpaEntitiesInInfrastructure(rootPackage),
                namesFollowConventions(rootPackage));
    }

    static ArchRule packagePlacement(String rootPackage) {
        return classes()
                .should(
                        new ArchCondition<JavaClass>(
                                "belong to the bootstrap, configuration or an existing module layer") {
                            @Override
                            public void check(JavaClass item, ConditionEvents events) {
                                boolean allowed =
                                        item.getName().equals(rootPackage + ".EmploiGoApplication")
                                                || inPackage(item, rootPackage + ".config")
                                                || layer(rootPackage, item).isPresent()
                                                || item.getName()
                                                        .equals(rootPackage + ".package-info");
                                events.add(
                                        new SimpleConditionEvent(
                                                item,
                                                allowed,
                                                item.getName()
                                                        + " must belong to bootstrap, config or <module>."
                                                        + LAYERS));
                            }
                        });
    }

    static ArchRule apiBoundaries(String rootPackage) {
        return noClasses()
                .that(inLayer(rootPackage, "api"))
                .should()
                .dependOnClassesThat(
                        predicate(
                                "are infrastructure, persistence APIs or integration SDKs",
                                target ->
                                        isLayer(rootPackage, target, "infrastructure")
                                                || inAnyPackage(target, PERSISTENCE_PACKAGES)
                                                || inAnyPackage(target, SDK_PACKAGES)))
                .because("HTTP adapters delegate persistence and external operations");
    }

    /**
     * Only the listed classes may depend on the password encoder, to keep every hash under the
     * limit of simultaneous computations. Not part of {@link #all}: it names classes of the
     * application.
     */
    static ArchRule passwordEncoderOnlyIn(String... allowedClassNames) {
        var allowed = java.util.List.of(allowedClassNames);
        return noClasses()
                .that(
                        predicate(
                                "are not one of " + allowed,
                                item -> !allowed.contains(item.getName())))
                .should()
                .dependOnClassesThat()
                .areAssignableTo(org.springframework.security.crypto.password.PasswordEncoder.class)
                .because(
                        "a computation outside the limit of simultaneous password computations escapes the protection");
    }

    private static final String SECOND_PROVIDER_RISK =
            "Spring Security would build a second global DaoAuthenticationProvider on the raw"
                    + " PasswordEncoder, outside the limit of PasswordHasher";

    /**
     * No bean is a {@link UserDetailsService} or an {@link AuthenticationProvider}, declared by a
     * {@code @Bean} method or by a component class. A class implementing one without being a bean
     * is allowed. Not part of {@link #all}: it names Spring Security.
     */
    static ArchRule noUserDetailsServiceNorAuthenticationProviderBean() {
        var beanMethods =
                noMethods()
                        .that()
                        .areAnnotatedWith(Bean.class)
                        .should()
                        .haveRawReturnType(assignableTo(UserDetailsService.class))
                        .orShould()
                        .haveRawReturnType(assignableTo(AuthenticationProvider.class))
                        .because(SECOND_PROVIDER_RISK);
        var components =
                noClasses()
                        .that(
                                predicate(
                                        "are Spring components",
                                        item ->
                                                item.isAnnotatedWith(Component.class)
                                                        || item.isMetaAnnotatedWith(
                                                                Component.class)))
                        .should()
                        .beAssignableTo(UserDetailsService.class)
                        .orShould()
                        .beAssignableTo(AuthenticationProvider.class)
                        .because(SECOND_PROVIDER_RISK);
        return CompositeArchRule.of(beanMethods).and(components);
    }

    private static final List<String> TRANSACTIONAL_PACKAGES =
            List.of(
                    "org.springframework.transaction",
                    "org.springframework.jdbc",
                    "org.springframework.orm",
                    "org.springframework.data.jpa",
                    "jakarta.persistence",
                    "java.sql",
                    "javax.sql",
                    "org.hibernate");

    /**
     * The classes of the quota path open no transaction, take no row lock and hold no pool
     * connection. Not part of {@link #all}: it names classes of the application.
     */
    static ArchRule withoutDatabaseNorTransaction(String... classNames) {
        var names = List.of(classNames);
        return noClasses()
                .that(predicate("are one of " + names, item -> names.contains(item.getName())))
                .should()
                .dependOnClassesThat(
                        predicate(
                                "belong to JDBC, JPA or a transaction",
                                target -> inAnyPackage(target, TRANSACTIONAL_PACKAGES)))
                .because(
                        "a counter that waits for Redis must not keep a database connection or a lock");
    }

    /**
     * No transactional method, annotated or in an annotated class, reaches the quota or Redis
     * through the calls of the classes under analysis: the wait for Redis must not hold a
     * connection or a lock. Redis is reached by a call to a class that depends on it, or to a type
     * of {@code rootPackage} that such a class extends or implements, as the ports of the limiters.
     * A method reference or a constructor call is not followed. Not part of {@link #all}: it
     * names a class of the application.
     */
    static ArchRule transactionalMethodsDoNotWaitForRedis(
            String rootPackage, String quotaClassName) {
        return methods()
                .that(methodPredicate("are transactional", ArchitectureRules::isTransactional))
                .should(
                        new ArchCondition<JavaMethod>("not reach the quota or Redis") {
                            @Override
                            public void check(JavaMethod item, ConditionEvents events) {
                                var caller =
                                        methodCallingRedis(item, rootPackage, quotaClassName);
                                events.add(
                                        new SimpleConditionEvent(
                                                item,
                                                caller.isEmpty(),
                                                item.getFullName()
                                                        + " reaches the quota or Redis through "
                                                        + caller.map(JavaMethod::getFullName)
                                                                .orElse("")));
                            }
                        })
                .because("a counter that waits for Redis must not be called in a transaction");
    }

    /**
     * The named method of the class is not transactional, neither by its own annotation nor by the
     * one of its class: it opens its own short transactions, if any.
     */
    static ArchRule methodIsOutsideATransaction(String className, String methodName) {
        return methods()
                .that(
                        methodPredicate(
                                "are " + className + "." + methodName,
                                item ->
                                        item.getOwner().getName().equals(className)
                                                && item.getName().equals(methodName)))
                .should(
                        new ArchCondition<JavaMethod>("not be transactional") {
                            @Override
                            public void check(JavaMethod item, ConditionEvents events) {
                                events.add(
                                        new SimpleConditionEvent(
                                                item,
                                                !isTransactional(item),
                                                item.getFullName() + " is transactional"));
                            }
                        })
                .because(
                        "an annotation here would keep a transaction open around its calls");
    }

    private static boolean dependsOnRedis(JavaClass item) {
        return item.getDirectDependenciesFromSelf().stream()
                .anyMatch(
                        dependency -> inAnyPackage(dependency.getTargetClass(), REDIS_PACKAGES));
    }

    private static boolean isTransactional(JavaMethod item) {
        return item.isAnnotatedWith(Transactional.class)
                || item.getOwner().isAnnotatedWith(Transactional.class);
    }

    private static Optional<JavaMethod> methodCallingRedis(
            JavaMethod start, String rootPackage, String quotaClassName) {
        var visited = new HashSet<JavaMethod>(List.of(start));
        var pending = new ArrayDeque<JavaMethod>(List.of(start));
        while (!pending.isEmpty()) {
            var current = pending.remove();
            for (var call : current.getMethodCallsFromSelf()) {
                var owner = call.getTargetOwner();
                if (owner.getName().equals(quotaClassName)
                        || inAnyPackage(owner, REDIS_PACKAGES)
                        || dependsOnRedis(owner)
                        || (owner.getName().startsWith(rootPackage + ".")
                                && owner.getAllSubclasses().stream()
                                        .anyMatch(ArchitectureRules::dependsOnRedis))) {
                    return Optional.of(current);
                }
                call.getTarget().resolveMember().filter(visited::add).ifPresent(pending::add);
            }
        }
        return Optional.empty();
    }

    /**
     * A class that writes to Redis takes the digest of an identity or of an address from the keyed
     * one, never from the plain SHA-256 of {@code AuthenticationSecrets}.
     */
    private static final List<String> REDIS_PACKAGES =
            List.of("org.springframework.data.redis", "io.lettuce");

    static ArchRule redisKeysWithoutPlainDigest() {
        return noClasses()
                .that(
                        predicate(
                                "depend on Redis",
                                ArchitectureRules::dependsOnRedis))
                .should()
                .dependOnClassesThat()
                .haveFullyQualifiedName("fr.emploigo.auth.domain.AuthenticationSecrets")
                .because(
                        "a plain digest of an email or of an address in a key of Redis can be reversed by a dictionary");
    }

    /**
     * A response declared on the class of a controller applies to every route, and replaces the one
     * of a route with the same status in the contract. Not part of {@link #all}: the fixtures of
     * that rule hold no controller.
     */
    static ArchRule routesDeclareTheirOwnResponses() {
        return noClasses()
                .that()
                .areAnnotatedWith(RestController.class)
                .should()
                .beAnnotatedWith(ApiResponse.class)
                .orShould()
                .beAnnotatedWith(ApiResponses.class)
                .because("a response of the class is declared on routes that cannot answer it");
    }

    /**
     * A Spring component, a registered {@code @ConfigurationProperties} or a Spring Data
     * repository carries exactly one of {@link ApiOnly}, {@link WorkerOnly} and {@link
     * ApiAndWorker}, and none names the worker in a {@code @Profile}. Not part of {@link #all}: its
     * fixtures hold no component.
     */
    static ArchRule componentsDeclareTheirRole(String rootPackage) {
        return classes()
                .that(springComponents(rootPackage))
                .should(
                        new ArchCondition<JavaClass>(
                                "carry exactly one of @ApiOnly, @WorkerOnly and @ApiAndWorker,"
                                        + " no @Profile that names the worker and no method role"
                                        + " that contradicts the class") {
                            @Override
                            public void check(JavaClass item, ConditionEvents events) {
                                long roles = ROLES.stream().filter(item::isAnnotatedWith).count();
                                add(events, item, roles == 1, "carries " + roles + " roles");
                                add(
                                        events,
                                        item,
                                        !namesWorkerInAProfile(item),
                                        "names the worker in a @Profile: use @ApiOnly or"
                                                + " @WorkerOnly");
                                add(
                                        events,
                                        item,
                                        !hasMethodRoleAgainstItsClass(item),
                                        "is reserved to the worker but has a method reserved to the"
                                                + " API");
                            }

                            private void add(
                                    ConditionEvents events,
                                    JavaClass item,
                                    boolean satisfied,
                                    String message) {
                                events.add(
                                        new SimpleConditionEvent(
                                                item, satisfied, item.getName() + " " + message));
                            }
                        })
                .because("the role of a component is declared by one annotation, defined once");
    }

    /**
     * Reads the profiles written on the class or its methods, not those of a meta-annotation, and
     * matches the word {@code worker} whole.
     */
    private static boolean namesWorkerInAProfile(JavaClass item) {
        var profiles = new ArrayList<Profile>();
        if (item.isAnnotatedWith(Profile.class)) {
            profiles.add(item.getAnnotationOfType(Profile.class));
        }
        item.getMethods().stream()
                .filter(method -> method.isAnnotatedWith(Profile.class))
                .forEach(method -> profiles.add(method.getAnnotationOfType(Profile.class)));
        return profiles.stream()
                .flatMap(profile -> Stream.of(profile.value()))
                .anyMatch(value -> value.matches(".*\\bworker\\b.*"));
    }

    private static boolean hasMethodRoleAgainstItsClass(JavaClass item) {
        return item.isAnnotatedWith(WorkerOnly.class)
                && item.getMethods().stream()
                        .anyMatch(method -> method.isAnnotatedWith(ApiOnly.class));
    }

    /**
     * The worker serves only its supervision (actuator, Spring Security): a shared or worker
     * component never touches Redis, sessions, a controller annotation or an {@code @ApiOnly}
     * class, and a shared one is not in an {@code api} package. Not part of {@link #all}: its
     * fixtures hold no component.
     */
    static ArchRule sharedComponentsAreFreeOfApiResources(String rootPackage) {
        return classes()
                .that()
                .areAnnotatedWith(ApiAndWorker.class)
                .or()
                .areAnnotatedWith(WorkerOnly.class)
                .should(
                        new ArchCondition<JavaClass>(
                                "not depend on an @ApiOnly class, Redis, sessions or a controller"
                                        + " annotation, and when shared, not be in an api package") {
                            @Override
                            public void check(JavaClass item, ConditionEvents events) {
                                boolean shared = item.isAnnotatedWith(ApiAndWorker.class);
                                if (shared && isLayer(rootPackage, item, "api")) {
                                    events.add(
                                            SimpleConditionEvent.violated(
                                                    item,
                                                    item.getName()
                                                            + " is shared but in an api package"));
                                }
                                for (var dependency : item.getDirectDependenciesFromSelf()) {
                                    var target = dependency.getTargetClass().getBaseComponentType();
                                    boolean forbidden =
                                            target.isAnnotatedWith(ApiOnly.class)
                                                    || isApiResource(target);
                                    if (forbidden) {
                                        events.add(
                                                SimpleConditionEvent.violated(
                                                        dependency,
                                                        dependency.getDescription()
                                                                + "; the worker has no such"
                                                                + " resource"));
                                    }
                                }
                            }
                        })
                .because("the worker has no controller, session, Redis or API component");
    }

    private static boolean isApiResource(JavaClass target) {
        return inPackage(target, "org.springframework.data.redis")
                || inPackage(target, "io.lettuce")
                || inPackage(target, "org.springframework.session")
                || inPackage(target, "org.springframework.web.bind.annotation")
                || target.getName().equals("jakarta.servlet.http.HttpSession")
                || target.getName().equals("org.springframework.stereotype.Controller");
    }

    /** A component of an {@code api} layer is never loaded by the worker, annotated or not. */
    static ArchRule apiComponentsAreReservedToTheApi(String rootPackage) {
        return classes()
                .that(springComponents(rootPackage))
                .and(inLayer(rootPackage, "api"))
                .should()
                .beAnnotatedWith(ApiOnly.class)
                .because("the worker has no HTTP controller, advice or API configuration");
    }

    private static DescribedPredicate<JavaClass> springComponents(String rootPackage) {
        return predicate(
                "are Spring components, configuration properties or repositories",
                item ->
                        inPackage(item, rootPackage)
                                && !item.getName().equals(rootPackage + ".EmploiGoApplication")
                                && ((item.isAnnotatedWith(Component.class)
                                                || item.isMetaAnnotatedWith(Component.class))
                                        || item.isAnnotatedWith(ConfigurationProperties.class)
                                        || (item.isInterface()
                                                && item.isAssignableTo(Repository.class))));
    }

    /**
     * An adapter reports a failure by the result of its call, and the use case raises it. Catching
     * an exception of the application layer is not raising it.
     */
    static ArchRule throwsNoApplicationException(String rootPackage) {
        return noClasses()
                .that(inLayer(rootPackage, "infrastructure"))
                .should()
                .callCodeUnitWhere(
                        new DescribedPredicate<JavaCall<?>>(
                                "creates an exception of an application layer") {
                            @Override
                            public boolean test(JavaCall<?> call) {
                                var target = call.getTarget();
                                if (!isLayer(rootPackage, target.getOwner(), "application"))
                                    return false;
                                if (target.getName().equals(CONSTRUCTOR_NAME))
                                    return target.getOwner().isAssignableTo(Throwable.class);
                                return isStatic(target)
                                        && isApplicationException(
                                                rootPackage, target.getRawReturnType());
                            }
                        })
                .because("an adapter reports a failure by the result of its port");
    }

    private static boolean isStatic(AccessTarget.CodeUnitCallTarget target) {
        return target.resolveMember()
                .map(method -> method.getModifiers().contains(JavaModifier.STATIC))
                .orElse(false);
    }

    private static boolean isApplicationException(String rootPackage, JavaClass type) {
        return isLayer(rootPackage, type, "application") && type.isAssignableTo(Throwable.class);
    }

    static ArchRule applicationBoundaries(String rootPackage) {
        return noClasses()
                .that(inLayer(rootPackage, "application"))
                .should()
                .dependOnClassesThat(
                        predicate(
                                "are HTTP adapters, HTTP/JSON APIs or integration SDKs",
                                target ->
                                        isLayer(rootPackage, target, "api")
                                                || inAnyPackage(target, HTTP_PACKAGES)
                                                || inAnyPackage(target, SDK_PACKAGES)))
                .because("use cases keep their inputs and outputs independent of HTTP and SDKs");
    }

    static ArchRule domainBoundaries(String rootPackage) {
        return noClasses()
                .that(inLayer(rootPackage, "domain"))
                .should()
                .dependOnClassesThat(
                        predicate(
                                "are outer layers, framework services, HTTP, persistence operations or integration SDKs",
                                target -> forbiddenDomainDependency(rootPackage, target)))
                .because(
                        "domain types may use JPA mappings and validation without performing infrastructure operations");
    }

    static ArchRule domainIsFreeOfPersistence(String rootPackage) {
        return noClasses()
                .that(inLayer(rootPackage, "domain"))
                .should()
                .dependOnClassesThat(
                        predicate(
                                "are JPA types, annotations included",
                                target -> inAnyPackage(target, JPA_PACKAGES)))
                .because(
                        "the persistence mapping belongs to infrastructure: the JPA entity is a separate class");
    }

    static ArchRule applicationIsFreeOfInfrastructureSpringDataOrJpa(String rootPackage) {
        return noClasses()
                .that(inLayer(rootPackage, "application"))
                .should()
                .dependOnClassesThat(
                        predicate(
                                "reside in infrastructure, or are Spring Data or JPA types",
                                target ->
                                        isLayer(rootPackage, target, "infrastructure")
                                                || inPackage(target, SPRING_DATA_PACKAGE)
                                                || inAnyPackage(target, JPA_PACKAGES)))
                .because(
                        "a use case reaches the database and the outside through the ports it declares");
    }

    static ArchRule jpaEntitiesAreJpaEntitiesInInfrastructure(String rootPackage) {
        return classes()
                .that(predicate("are JPA entities", item -> item.isAnnotatedWith(Entity.class)))
                .should(
                        new ArchCondition<JavaClass>(
                                "reside in infrastructure and have a name ending with JpaEntity") {
                            @Override
                            public void check(JavaClass item, ConditionEvents events) {
                                report(
                                        events,
                                        item,
                                        isLayer(rootPackage, item, "infrastructure"),
                                        "is a JPA entity outside infrastructure");
                                report(
                                        events,
                                        item,
                                        item.getSimpleName().endsWith("JpaEntity"),
                                        "is a JPA entity not named with the suffix JpaEntity");
                            }
                        })
                .because("a JPA entity is an adapter's type, and its name shows it");
    }

    /**
     * Forbidden words, the I prefix and the role suffixes of {@code conventions.md}. A
     * {@code SpringData} prefix on an interface is allowed. The UseCase suffix is not tied to an
     * annotation, so only its layer is checked.
     */
    static ArchRule namesFollowConventions(String rootPackage) {
        return classes()
                .should(
                        new ArchCondition<JavaClass>(
                                "name no forbidden word, no I prefix on an interface, and the suffix"
                                        + " of its role") {
                            @Override
                            public void check(JavaClass item, ConditionEvents events) {
                                var name = item.getSimpleName();
                                var typeName =
                                        item.isInterface() && name.startsWith(SPRING_DATA_PREFIX)
                                                ? name.substring(SPRING_DATA_PREFIX.length())
                                                : name;
                                var forbidden =
                                        words(typeName).stream()
                                                .filter(FORBIDDEN_NAME_WORDS::contains)
                                                .findFirst();
                                report(
                                        events,
                                        item,
                                        forbidden.isEmpty(),
                                        "contains the word "
                                                + forbidden.orElse("")
                                                + ", forbidden by the naming conventions");
                                report(
                                        events,
                                        item,
                                        !(item.isInterface() && name.matches("I[A-Z].*")),
                                        "is an interface with an I prefix");
                                report(
                                        events,
                                        item,
                                        !item.isAnnotatedWith(RestController.class)
                                                || name.endsWith("Controller"),
                                        "is a @RestController not named with the suffix Controller");
                                report(
                                        events,
                                        item,
                                        !item.isAnnotatedWith(Configuration.class)
                                                || name.endsWith("Configuration"),
                                        "is a @Configuration not named with the suffix Configuration");
                                report(
                                        events,
                                        item,
                                        !item.isAnnotatedWith(ConfigurationProperties.class)
                                                || name.endsWith("Properties"),
                                        "is a @ConfigurationProperties not named with the suffix"
                                                + " Properties");
                                report(
                                        events,
                                        item,
                                        !item.isAssignableTo(Throwable.class)
                                                || name.endsWith("Exception"),
                                        "is a Throwable not named with the suffix Exception");
                                report(
                                        events,
                                        item,
                                        !name.endsWith("UseCase")
                                                || isLayer(rootPackage, item, "application"),
                                        "is named with the suffix UseCase outside the application"
                                                + " layer");
                            }
                        })
                .because("the name of a class states its role, and the architecture check reads it");
    }

    private static List<String> words(String name) {
        return List.of(name.split("(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])"));
    }

    private static void report(
            ConditionEvents events, JavaClass item, boolean satisfied, String message) {
        events.add(new SimpleConditionEvent(item, satisfied, item.getName() + " " + message));
    }

    static ArchRule infrastructureBoundaries(String rootPackage) {
        return noClasses()
                .that(inLayer(rootPackage, "infrastructure"))
                .should()
                .dependOnClassesThat(inLayer(rootPackage, "api"))
                .because(
                        "infrastructure adapters do not depend on HTTP controllers or response DTOs");
    }

    static ArchRule configurationBoundaries(String rootPackage) {
        return noClasses()
                .that()
                .resideInAPackage(rootPackage + ".config..")
                .should()
                .dependOnClassesThat(
                        predicate(
                                "belong to a module",
                                target -> module(rootPackage, target).isPresent()))
                .because("the shared configuration does not assemble individual modules");
    }

    static ArchRule moduleBoundaries(String rootPackage) {
        return classes()
                .that(
                        predicate(
                                "belong to a module",
                                item -> module(rootPackage, item).isPresent()))
                .should(
                        new ArchCondition<JavaClass>(
                                "use other modules only through public application types") {
                            @Override
                            public void check(JavaClass item, ConditionEvents events) {
                                var originModule = module(rootPackage, item).orElseThrow();
                                for (var dependency : item.getDirectDependenciesFromSelf()) {
                                    var target = dependency.getTargetClass().getBaseComponentType();
                                    var targetModule = module(rootPackage, target);
                                    if (targetModule.isPresent()
                                            && !originModule.equals(targetModule.get())) {
                                        boolean allowed =
                                                target.getPackageName()
                                                                .equals(
                                                                        rootPackage
                                                                                + "."
                                                                                + targetModule.get()
                                                                                + ".application")
                                                        && isPublic(target);
                                        events.add(
                                                new SimpleConditionEvent(
                                                        dependency,
                                                        allowed,
                                                        dependency.getDescription()
                                                                + "; cross-module target must be a public type in "
                                                                + rootPackage
                                                                + "."
                                                                + targetModule.get()
                                                                + ".application"));
                                    }
                                }
                            }
                        });
    }

    static ArchRule moduleCycles(String rootPackage) {
        return slices().assignedFrom(
                        new SliceAssignment() {
                            @Override
                            public SliceIdentifier getIdentifierOf(JavaClass javaClass) {
                                return module(rootPackage, javaClass)
                                        .map(SliceIdentifier::of)
                                        .orElseGet(SliceIdentifier::ignore);
                            }

                            @Override
                            public String getDescription() {
                                return "modules directly below "
                                        + rootPackage
                                        + " (excluding bootstrap and config)";
                            }
                        })
                .should()
                .beFreeOfCycles();
    }

    /**
     * The subjects of a domain, a sub-package named the same in each layer, do not depend on
     * each other in a cycle. Classes placed directly in a layer belong to no subject.
     */
    static ArchRule subjectsOfADomainAreFreeOfCycles(String rootPackage) {
        return slices().assignedFrom(
                        new SliceAssignment() {
                            @Override
                            public SliceIdentifier getIdentifierOf(JavaClass javaClass) {
                                return subject(rootPackage, javaClass)
                                        .map(SliceIdentifier::of)
                                        .orElseGet(SliceIdentifier::ignore);
                            }

                            @Override
                            public String getDescription() {
                                return "subjects of each domain, all layers together";
                            }
                        })
                .should()
                .beFreeOfCycles();
    }

    /** The domain and the subject of a class in a sub-package of a layer, if any. */
    private static Optional<String> subject(String rootPackage, JavaClass item) {
        return module(rootPackage, item)
                .flatMap(
                        module -> {
                            String prefix = rootPackage + "." + module + ".";
                            String packageName = item.getPackageName();
                            if (!packageName.startsWith(prefix)) {
                                return Optional.empty();
                            }
                            String[] segments =
                                    packageName.substring(prefix.length()).split("\\.");
                            return segments.length >= 2 && LAYERS.contains(segments[0])
                                    ? Optional.of(module + "." + segments[1])
                                    : Optional.empty();
                        });
    }

    private static boolean forbiddenDomainDependency(String rootPackage, JavaClass target) {
        target = target.getBaseComponentType();
        if (inPackage(target, rootPackage) && !isLayer(rootPackage, target, "domain")) {
            return true;
        }
        if (inPackage(target, "jakarta.persistence") || inPackage(target, "jakarta.validation")) {
            return !target.isAnnotation() && !target.isEnum();
        }
        return inAnyPackage(target, HTTP_PACKAGES)
                || inAnyPackage(target, PERSISTENCE_PACKAGES)
                || inAnyPackage(target, SDK_PACKAGES)
                || inPackage(target, "org.springframework")
                || inPackage(target, "jakarta")
                || inPackage(target, "io.micrometer");
    }

    private static DescribedPredicate<JavaClass> inLayer(String rootPackage, String layer) {
        return predicate(
                "reside in a module's " + layer + " layer",
                item -> isLayer(rootPackage, item, layer));
    }

    private static DescribedPredicate<JavaClass> predicate(
            String description, Predicate<JavaClass> predicate) {
        return new DescribedPredicate<>(description) {
            @Override
            public boolean test(JavaClass item) {
                return predicate.test(item.getBaseComponentType());
            }
        };
    }

    private static DescribedPredicate<JavaMethod> methodPredicate(
            String description, Predicate<JavaMethod> predicate) {
        return new DescribedPredicate<>(description) {
            @Override
            public boolean test(JavaMethod item) {
                return predicate.test(item);
            }
        };
    }

    private static Optional<String> module(String rootPackage, JavaClass item) {
        String prefix = rootPackage + ".";
        String packageName = item.getPackageName();
        if (!packageName.startsWith(prefix)) {
            return Optional.empty();
        }
        String first = packageName.substring(prefix.length()).split("\\.", 2)[0];
        return first.equals("config") ? Optional.empty() : Optional.of(first);
    }

    private static Optional<String> layer(String rootPackage, JavaClass item) {
        return module(rootPackage, item)
                .flatMap(
                        module -> {
                            String prefix = rootPackage + "." + module + ".";
                            String packageName = item.getPackageName();
                            if (!packageName.startsWith(prefix)) {
                                return Optional.empty();
                            }
                            String first =
                                    packageName.substring(prefix.length()).split("\\.", 2)[0];
                            return LAYERS.contains(first) ? Optional.of(first) : Optional.empty();
                        });
    }

    private static boolean isLayer(String rootPackage, JavaClass item, String expected) {
        return layer(rootPackage, item).filter(expected::equals).isPresent();
    }

    private static boolean inPackage(JavaClass item, String packageName) {
        return item.getPackageName().equals(packageName)
                || item.getPackageName().startsWith(packageName + ".");
    }

    private static boolean inAnyPackage(JavaClass item, List<String> packages) {
        return packages.stream().anyMatch(packageName -> inPackage(item, packageName));
    }

    private static boolean isPublic(JavaClass item) {
        return item.getModifiers().contains(JavaModifier.PUBLIC)
                && item.getEnclosingClass().map(ArchitectureRules::isPublic).orElse(true);
    }
}
