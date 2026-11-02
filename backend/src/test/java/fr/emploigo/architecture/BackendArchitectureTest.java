package fr.emploigo.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.properties.CanBeAnnotated;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;

import fr.emploigo.auth.application.password.PasswordHasher;
import fr.emploigo.auth.application.quota.LoginAttempt;
import fr.emploigo.auth.application.quota.QuotaEnforcer;
import fr.emploigo.auth.application.session.AuthenticationSessions;
import fr.emploigo.auth.application.session.ResumeSessionUseCase;
import fr.emploigo.auth.infrastructure.quota.CounterLossReport;
import fr.emploigo.auth.infrastructure.quota.QuotaKeys;
import fr.emploigo.auth.infrastructure.quota.RedisAuthenticationRateLimiter;
import fr.emploigo.auth.infrastructure.quota.RedisCall;
import fr.emploigo.auth.infrastructure.quota.RedisLoginAttemptLimiter;
import fr.emploigo.auth.infrastructure.security.AuthenticationSecurityConfiguration;
import fr.emploigo.auth.infrastructure.session.SpringSecurityPasswordChecker;
import fr.emploigo.config.ApiAndWorker;
import fr.emploigo.config.ApiOnly;
import fr.emploigo.config.WorkerOnly;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.lang.annotation.Annotation;
import java.util.List;
import java.util.stream.Stream;

class BackendArchitectureTest {
    // Package-private, so named by its name rather than by a class literal.
    private static final String RECONNECTION_REVOKER =
            "fr.emploigo.auth.application.session.ReconnectionRevoker";
    private static final JavaClasses PRODUCTION_CLASSES =
            new ClassFileImporter()
                    .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                    .importPackages("fr.emploigo");

    @Test
    void productionClassesAreImported() {
        assertThat(PRODUCTION_CLASSES).as("production classes under fr.emploigo").isNotEmpty();
    }

    /**
     * Listed by name so that a renamed package or a lost layer fails here. A module absent from the
     * list, such as {@code status}, may keep fewer layers.
     */
    static Stream<String> requiredLayers() {
        return Stream.of("auth", "establishment")
                .flatMap(
                        module ->
                                ArchitectureRules.LAYERS.stream()
                                        .map(layer -> "fr.emploigo." + module + "." + layer));
    }

    @ParameterizedTest
    @MethodSource("requiredLayers")
    void listedModulesKeepTheirLayers(String layerPackage) {
        var packages = PRODUCTION_CLASSES.stream().map(JavaClass::getPackageName).toList();

        assertThat(packages)
                .as("production classes in " + layerPackage)
                .anyMatch(name -> name.equals(layerPackage) || name.startsWith(layerPackage + "."));
    }

    @Test
    void productionClassesRespectModuleAndLayerBoundaries() {
        assertAll(
                ArchitectureRules.all("fr.emploigo").stream()
                        .map(rule -> (Executable) () -> rule.check(PRODUCTION_CLASSES)));
    }

    @Test
    void everyComponentDeclaresItsRole() {
        assertAll(
                () ->
                        ArchitectureRules.componentsDeclareTheirRole("fr.emploigo")
                                .check(PRODUCTION_CLASSES),
                () ->
                        ArchitectureRules.apiComponentsAreReservedToTheApi("fr.emploigo")
                                .check(PRODUCTION_CLASSES),
                () ->
                        ArchitectureRules.sharedComponentsAreFreeOfApiResources("fr.emploigo")
                                .check(PRODUCTION_CLASSES));
    }

    @ParameterizedTest
    @ValueSource(classes = {ApiOnly.class, WorkerOnly.class, ApiAndWorker.class})
    void eachRoleIsUsedByAComponent(Class<? extends Annotation> role) {
        assertThat(PRODUCTION_CLASSES.that(CanBeAnnotated.Predicates.annotatedWith(role)))
                .as("production classes annotated with " + role.getSimpleName())
                .isNotEmpty();
    }

    @Test
    void controllersDeclareResponsesOnRoutesOnly() {
        ArchitectureRules.routesDeclareTheirOwnResponses().check(PRODUCTION_CLASSES);
    }

    /**
     * Every password computation goes through {@link PasswordHasher}, which caps them, Spring
     * Security's login included; the classes allowed to use an encoder are asserted to depend on
     * it, so a rename cannot make the rule pass empty.
     */
    @Test
    void onlyPasswordHasherItsConfigurationAndTheLoginDependOnThePasswordEncoder() {
        var allowed =
                List.of(
                        PasswordHasher.class.getName(),
                        AuthenticationSecurityConfiguration.class.getName(),
                        SpringSecurityPasswordChecker.class.getName(),
                        "fr.emploigo.auth.infrastructure.session.CappedPasswordEncoder");
        var allowedSeeEncoder =
                allowed.stream()
                        .map(
                                name ->
                                        (Executable)
                                                () ->
                                                        classes()
                                                                .that()
                                                                .haveFullyQualifiedName(name)
                                                                .should()
                                                                .dependOnClassesThat()
                                                                .areAssignableTo(
                                                                        PasswordEncoder.class)
                                                                .check(PRODUCTION_CLASSES));
        Executable othersDoNot =
                () ->
                        ArchitectureRules.passwordEncoderOnlyIn(allowed.toArray(String[]::new))
                                .check(PRODUCTION_CLASSES);

        assertAll(Stream.concat(allowedSeeEncoder, Stream.of(othersDoNot)));
    }

    @Test
    void noBeanIsAUserDetailsServiceNorAnAuthenticationProvider() {
        ArchitectureRules.noUserDetailsServiceNorAuthenticationProviderBean()
                .check(PRODUCTION_CLASSES);
    }

    private static final List<String> QUOTA_PATH =
            Stream.of(
                            QuotaEnforcer.class,
                            LoginAttempt.class,
                            RedisAuthenticationRateLimiter.class,
                            RedisLoginAttemptLimiter.class,
                            QuotaKeys.class,
                            CounterLossReport.class,
                            RedisCall.class)
                    .map(Class::getName)
                    .toList();

    @Test
    void quotaPathUsesNeitherTheDatabaseNorATransaction() {
        ArchitectureRules.withoutDatabaseNorTransaction(QUOTA_PATH.toArray(String[]::new))
                .check(PRODUCTION_CLASSES);
    }

    @Test
    void noTransactionalMethodWaitsForRedis() {
        ArchitectureRules.transactionalMethodsDoNotWaitForRedis(
                        "fr.emploigo", QuotaEnforcer.class.getName())
                .check(PRODUCTION_CLASSES);
    }

    /**
     * Each method is a rule of its own, so that a rename of one cannot leave the other to carry
     * the check.
     */
    @Test
    void resumeAndRevocationOpenNoTransaction() {
        assertAll(
                () ->
                        ArchitectureRules.methodIsOutsideATransaction(
                                        ResumeSessionUseCase.class.getName(), "resume")
                                .check(PRODUCTION_CLASSES),
                () ->
                        ArchitectureRules.methodIsOutsideATransaction(
                                        AuthenticationSessions.class.getName(), "revoke")
                                .check(PRODUCTION_CLASSES),
                () ->
                        ArchitectureRules.methodIsOutsideATransaction(
                                        RECONNECTION_REVOKER, "revoke")
                                .check(PRODUCTION_CLASSES));
    }

    @Test
    void redisKeysOfTheQuotasNameNoValueByAPlainDigest() {
        ArchitectureRules.redisKeysWithoutPlainDigest().check(PRODUCTION_CLASSES);
    }

    @Test
    void infrastructureThrowsNoApplicationException() {
        ArchitectureRules.throwsNoApplicationException("fr.emploigo").check(PRODUCTION_CLASSES);
    }
}
