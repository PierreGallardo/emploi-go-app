package fr.emploigo.architecture;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import architecture.fixtures.alpha.api.ApiFixtures;
import architecture.fixtures.alpha.application.ApplicationFixtures;
import architecture.fixtures.alpha.application.NamingFixtures;
import architecture.fixtures.alpha.domain.DomainFixtures;
import architecture.fixtures.alpha.infrastructure.InfrastructureFixtures;
import architecture.fixtures.beta.application.OtherModuleFixtures;
import architecture.fixtures.config.ConfigurationFixtures;
import architecture.fixtures.roles.RoleFixtures;
import architecture.fixtures.subjects.api.first.FirstRoute;
import architecture.fixtures.subjects.application.Hub;
import architecture.fixtures.subjects.application.first.FirstFixtures;
import architecture.fixtures.subjects.application.second.SecondFixtures;
import architecture.fixtures.subjects.infrastructure.second.SecondAdapter;
import architecture.fixtures.unplaced.UnplacedFixture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

class ArchitectureRulesTest {
    private static final String ROOT = "architecture.fixtures";

    @Test
    void allowedCollaborationsMappingsAndWorkerCallsAreAccepted() {
        var classes =
                new ClassFileImporter()
                        .importClasses(
                                ApiFixtures.Allowed.class,
                                ApplicationFixtures.Allowed.class,
                                DomainFixtures.Offer.class,
                                FirstFixtures.Allowed.class,
                                SecondFixtures.DependsOnFirst.class,
                                InfrastructureFixtures.Allowed.class,
                                InfrastructureFixtures.OfferJpaEntity.class,
                                OtherModuleFixtures.Allowed.class,
                                ConfigurationFixtures.Allowed.class);
        assertAll(
                ArchitectureRules.all(ROOT).stream()
                        .map(rule -> (Executable) () -> rule.check(classes)));
    }

    @Test
    void unclassifiedProductionPackagesAreRejected() {
        assertRejected(ArchitectureRules.packagePlacement(ROOT), UnplacedFixture.class);
    }

    @Test
    void apiCannotAccessInfrastructureJdbcOrSdkDirectly() {
        var rule = ArchitectureRules.apiBoundaries(ROOT);
        assertRejected(rule, ApiFixtures.WithInfrastructure.class);
        assertRejected(rule, ApiFixtures.WithJpaOperations.class);
        assertRejected(rule, ApiFixtures.WithJdbc.class);
        assertRejected(rule, ApiFixtures.WithSdk.class);
    }

    @Test
    void aControllerCannotDeclareAResponseOnItsClass() {
        var rule = ArchitectureRules.routesDeclareTheirOwnResponses();
        assertRejected(rule, ApiFixtures.WithClassResponse.class);
        assertRejected(rule, ApiFixtures.WithClassResponses.class);
    }

    @Test
    void aControllerCanDeclareResponsesOnItsRoutes() {
        ArchitectureRules.routesDeclareTheirOwnResponses()
                .check(new ClassFileImporter().importClasses(ApiFixtures.WithRouteResponsesOnly.class));
    }

    @Test
    void applicationCannotAccessApiHttpOrSdkDirectly() {
        var rule = ArchitectureRules.applicationBoundaries(ROOT);
        assertRejected(rule, ApplicationFixtures.WithApi.class);
        assertRejected(rule, ApplicationFixtures.WithHttp.class);
        assertRejected(rule, ApplicationFixtures.WithSdk.class);
        assertRejected(rule, ApplicationFixtures.WithRedis.class);
    }

    @Test
    void domainCannotUseOuterLayersSpringOrPersistenceOperations() {
        var rule = ArchitectureRules.domainBoundaries(ROOT);
        assertRejected(rule, DomainFixtures.WithApplication.class);
        assertRejected(rule, DomainFixtures.WithInfrastructure.class);
        assertRejected(rule, DomainFixtures.WithApi.class);
        assertRejected(rule, DomainFixtures.WithSpring.class);
        assertRejected(rule, DomainFixtures.WithEntityManager.class);
        assertRejected(rule, DomainFixtures.WithValidator.class);
        assertRejected(rule, DomainFixtures.WithJdbc.class);
        assertRejected(rule, DomainFixtures.WithHttp.class);
        assertRejected(rule, DomainFixtures.WithSdk.class);
    }

    @Test
    void infrastructureCannotAccessApi() {
        assertRejected(
                ArchitectureRules.infrastructureBoundaries(ROOT),
                InfrastructureFixtures.WithApi.class);
    }

    @Test
    void onlyTheListedClassesCanDependOnThePasswordEncoder() {
        assertRejected(
                ArchitectureRules.passwordEncoderOnlyIn(),
                ApplicationFixtures.WithPasswordEncoder.class);
        assertRejected(
                ArchitectureRules.passwordEncoderOnlyIn(
                        ApplicationFixtures.Allowed.class.getName()),
                ApplicationFixtures.WithPasswordEncoder.class);
        var classes =
                new ClassFileImporter()
                        .importClasses(
                                ApplicationFixtures.WithPasswordEncoder.class,
                                ApplicationFixtures.Allowed.class);
        ArchitectureRules.passwordEncoderOnlyIn(
                        ApplicationFixtures.WithPasswordEncoder.class.getName())
                .check(classes);
    }

    @Test
    void noBeanCanBeAUserDetailsServiceOrAnAuthenticationProvider() {
        var rule = ArchitectureRules.noUserDetailsServiceNorAuthenticationProviderBean();
        var rejected =
                List.of(
                        ApplicationFixtures.WithUserDetailsServiceBeanMethod.class,
                        ApplicationFixtures.WithAuthenticationProviderSubtypeBeanMethod.class,
                        ApplicationFixtures.UserDetailsServiceComponent.class,
                        ApplicationFixtures.AuthenticationProviderComponent.class);
        var allowed = ApplicationFixtures.UserDetailsServiceWithoutBean.class;
        var classes =
                new ClassFileImporter()
                        .importClasses(
                                Stream.concat(rejected.stream(), Stream.of(allowed))
                                        .toArray(Class<?>[]::new));

        var details = String.join("\n", rule.evaluate(classes).getFailureReport().getDetails());

        assertAll(
                Stream.concat(
                        rejected.stream()
                                .map(
                                        fixture ->
                                                (Executable)
                                                        () ->
                                                                assertTrue(
                                                                        details.contains(
                                                                                fixture.getName()),
                                                                        () ->
                                                                                "should reject "
                                                                                        + fixture
                                                                                                .getName())),
                        Stream.of(
                                () ->
                                        assertFalse(
                                                details.contains(allowed.getName() + "\n")
                                                        || details.contains(
                                                                allowed.getName() + " "),
                                                "should accept a class that is not a bean"))));
    }

    @Test
    void quotaPathCannotUseJdbcOrATransaction() {
        var names =
                new String[] {
                    ApplicationFixtures.WithJdbcTemplate.class.getName(),
                    ApplicationFixtures.WithTransaction.class.getName(),
                    ApplicationFixtures.Counter.class.getName()
                };
        var rule = ArchitectureRules.withoutDatabaseNorTransaction(names);
        assertRejected(rule, ApplicationFixtures.WithJdbcTemplate.class);
        assertRejected(rule, ApplicationFixtures.WithTransaction.class);
        rule.check(new ClassFileImporter().importClasses(ApplicationFixtures.Counter.class));
    }

    private static ArchRule noWaitForRedisInATransaction() {
        return ArchitectureRules.transactionalMethodsDoNotWaitForRedis(
                ROOT, ApplicationFixtures.QuotaCounter.class.getName());
    }

    @Test
    void aTransactionalMethodCannotCallTheQuota() {
        assertReached(
                ApplicationFixtures.TransactionalCallingQuota.class,
                ApplicationFixtures.QuotaCounter.class);
    }

    @Test
    void aMethodOfATransactionalClassCannotCallTheQuota() {
        assertReached(
                ApplicationFixtures.TransactionalClassCallingQuota.class,
                ApplicationFixtures.QuotaCounter.class);
    }

    @Test
    void aTransactionalMethodCannotReachTheQuotaThroughAnotherBean() {
        assertReached(
                ApplicationFixtures.TransactionalCallingQuotaThroughAnotherBean.class,
                ApplicationFixtures.Gate.class,
                ApplicationFixtures.QuotaCounter.class);
    }

    @Test
    void aTransactionalMethodCannotReachTheQuotaInTwoHops() {
        assertReached(
                ApplicationFixtures.TransactionalCallingQuotaInTwoHops.class,
                ApplicationFixtures.Relay.class,
                ApplicationFixtures.Gate.class,
                ApplicationFixtures.QuotaCounter.class);
    }

    @Test
    void aCycleThatReachesTheQuotaIsRejected() {
        assertReached(
                ApplicationFixtures.TransactionalCycleReachingQuota.class,
                ApplicationFixtures.QuotaCounter.class);
    }

    @Test
    void aTransactionalMethodCannotCallAPortImplementedOverRedis() {
        assertReached(
                ApplicationFixtures.TransactionalCallingRedisPort.class,
                ApplicationFixtures.LimiterPort.class,
                ApplicationFixtures.RedisLimiter.class);
    }

    @Test
    void aTransactionalMethodCannotCallAClassThatDependsOnRedis() {
        assertReached(
                ApplicationFixtures.TransactionalCallingRedisAdapter.class,
                ApplicationFixtures.RedisLimiter.class);
    }

    @Test
    void aTransactionalMethodCannotCallRedisDirectly() {
        assertReached(ApplicationFixtures.TransactionalCallingRedisTemplate.class);
    }

    @Test
    @Timeout(10)
    void aMethodCountingBeforeTheTransactionIsAccepted() {
        noWaitForRedisInATransaction()
                .check(
                        new ClassFileImporter()
                                .importClasses(
                                        ApplicationFixtures.CountingBeforeTheTransaction.class,
                                        ApplicationFixtures.TransactionalWithoutQuota.class,
                                        ApplicationFixtures.TransactionalCycle.class,
                                        ApplicationFixtures.TransactionalCallingPlainPort.class,
                                        ApplicationFixtures.PlainPort.class,
                                        ApplicationFixtures.PlainImplementation.class,
                                        ApplicationFixtures.Gate.class,
                                        ApplicationFixtures.QuotaCounter.class));
    }

    private static void assertReached(Class<?> fixture, Class<?>... collaborators) {
        var rule = noWaitForRedisInATransaction();
        var classes =
                new ClassFileImporter()
                        .importClasses(
                                Stream.concat(Stream.of(fixture), Stream.of(collaborators))
                                        .toArray(Class<?>[]::new));
        var report = rule.evaluate(classes).getFailureReport().toString();
        assertTrue(
                report.contains(fixture.getName() + ".act"),
                () ->
                        rule.getDescription()
                                + " should reject "
                                + fixture.getName()
                                + ": "
                                + report);
    }

    @Test
    void aNamedMethodCannotBeAnnotatedAsTransactional() {
        assertRejected(
                ArchitectureRules.methodIsOutsideATransaction(
                        ApplicationFixtures.TransactionalWithoutQuota.class.getName(), "act"),
                ApplicationFixtures.TransactionalWithoutQuota.class);
    }

    @Test
    void aNamedMethodCannotBeTransactionalThroughItsClass() {
        assertRejected(
                ArchitectureRules.methodIsOutsideATransaction(
                        ApplicationFixtures.TransactionalClassCallingQuota.class.getName(), "act"),
                ApplicationFixtures.TransactionalClassCallingQuota.class);
    }

    @Test
    void aNamedMethodOutsideATransactionIsAccepted() {
        ArchitectureRules.methodIsOutsideATransaction(
                        ApplicationFixtures.CountingBeforeTheTransaction.class.getName(), "act")
                .check(
                        new ClassFileImporter()
                                .importClasses(
                                        ApplicationFixtures.CountingBeforeTheTransaction.class));
    }

    @Test
    void aNamedMethodThatDoesNotExistIsRejected() {
        assertChecksNothing(
                ArchitectureRules.methodIsOutsideATransaction(
                        ApplicationFixtures.CountingBeforeTheTransaction.class.getName(), "absent"),
                new ClassFileImporter()
                        .importClasses(ApplicationFixtures.CountingBeforeTheTransaction.class));
    }

    @Test
    void aClassThatWritesToRedisCannotNameAKeyByAPlainDigest() {
        var rule = ArchitectureRules.redisKeysWithoutPlainDigest();
        assertRejected(rule, InfrastructureFixtures.WithRedisAndPlainDigest.class);
        rule.check(
                new ClassFileImporter()
                        .importClasses(InfrastructureFixtures.WithRedisAndKeyedDigest.class));
    }

    @Test
    void infrastructureCannotCreateAnApplicationException() {
        var rule = ArchitectureRules.throwsNoApplicationException(ROOT);
        assertRejected(rule, InfrastructureFixtures.WithApplicationException.class);
        assertRejected(rule, InfrastructureFixtures.WithApplicationExceptionFactory.class);
        assertTrue(
                rule.evaluate(
                                new ClassFileImporter()
                                        .importClasses(
                                                InfrastructureFixtures
                                                        .WithApplicationExceptionFromAnotherFactory
                                                        .class,
                                                ApplicationFixtures.FailureFactory.class))
                        .hasViolation(),
                "a static factory of the application layer that returns its exception");
        rule.check(
                new ClassFileImporter()
                        .importClasses(
                                InfrastructureFixtures.Allowed.class,
                                InfrastructureFixtures.WithDomainFailure.class,
                                InfrastructureFixtures.CatchingAnApplicationException.class));
    }

    @Test
    void sharedConfigurationCannotAccessModules() {
        assertRejected(
                ArchitectureRules.configurationBoundaries(ROOT),
                ConfigurationFixtures.WithModule.class);
    }

    @Test
    void otherModulesCannotAccessDomainInfrastructureApiOrInternalApplicationTypes() {
        var rule = ArchitectureRules.moduleBoundaries(ROOT);
        assertRejected(rule, OtherModuleFixtures.WithForeignDomain.class);
        assertRejected(rule, OtherModuleFixtures.WithForeignInfrastructure.class);
        assertRejected(rule, OtherModuleFixtures.WithForeignApi.class);
        assertRejected(rule, OtherModuleFixtures.WithInternalApplication.class);
    }

    @Test
    void cyclesBetweenModulesAreRejectedEvenThroughPublicApplicationTypes() {
        var classes =
                new ClassFileImporter()
                        .importClasses(
                                ApplicationFixtures.Cycle.class, OtherModuleFixtures.Cycle.class);
        ArchitectureRules.moduleBoundaries(ROOT).check(classes);
        assertTrue(ArchitectureRules.moduleCycles(ROOT).evaluate(classes).hasViolation());
    }

    @Test
    void subjectsOfADomainWithoutCycleAreAccepted() {
        var classes =
                new ClassFileImporter()
                        .importClasses(
                                FirstFixtures.Allowed.class, SecondFixtures.DependsOnFirst.class);
        ArchitectureRules.subjectsOfADomainAreFreeOfCycles(ROOT).check(classes);
    }

    @Test
    void cycleBetweenSubjectsOfOneLayerIsRejected() {
        var classes =
                new ClassFileImporter()
                        .importClasses(
                                FirstFixtures.DependsOnSecond.class,
                                SecondFixtures.DependsOnFirst.class,
                                FirstFixtures.Allowed.class);
        assertTrue(
                ArchitectureRules.subjectsOfADomainAreFreeOfCycles(ROOT)
                        .evaluate(classes)
                        .hasViolation());
    }

    @Test
    void cycleBetweenSubjectsThroughTwoLayersIsRejected() {
        var classes =
                new ClassFileImporter()
                        .importClasses(
                                FirstRoute.class,
                                SecondAdapter.class,
                                SecondFixtures.Allowed.class,
                                FirstFixtures.Allowed.class);
        assertTrue(
                ArchitectureRules.subjectsOfADomainAreFreeOfCycles(ROOT)
                        .evaluate(classes)
                        .hasViolation());
    }

    @Test
    void classPlacedDirectlyInALayerBelongsToNoSubject() {
        var classes =
                new ClassFileImporter()
                        .importClasses(
                                FirstFixtures.DependsOnHub.class,
                                Hub.class,
                                FirstFixtures.Allowed.class);
        ArchitectureRules.subjectsOfADomainAreFreeOfCycles(ROOT).check(classes);
    }

    @Test
    void componentsWithOneRoleAreAccepted() {
        var classes =
                new ClassFileImporter()
                        .importClasses(
                                RoleFixtures.ReservedToTheApi.class,
                                RoleFixtures.ReservedToTheWorker.class,
                                RoleFixtures.Shared.class,
                                RoleFixtures.ReservedRepository.class,
                                RoleFixtures.ReservedProperties.class,
                                RoleFixtures.ReservedToTheLocalApi.class,
                                RoleFixtures.ReservedToAProfileThatMerelyContainsTheWord.class);
        ArchitectureRules.componentsDeclareTheirRole(ROOT).check(classes);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("componentsWithAFaultInTheirRole")
    void componentsWithoutExactlyOneRoleOrWithAWorkerProfileAreRejected(
            Class<?> fixture, String violation) {
        assertRejectedFor(ArchitectureRules.componentsDeclareTheirRole(ROOT), fixture, violation);
    }

    static Stream<Arguments> componentsWithAFaultInTheirRole() {
        return Stream.of(
                arguments(RoleFixtures.WithoutRole.class, "carries 0 roles"),
                arguments(RoleFixtures.WithTwoRoles.class, "carries 2 roles"),
                arguments(RoleFixtures.WithWorkerProfile.class, "names the worker"),
                arguments(RoleFixtures.WithNegatedWorkerProfile.class, "names the worker"),
                arguments(RoleFixtures.WithWorkerProfileOnABean.class, "names the worker"),
                arguments(RoleFixtures.WithAnApiMethod.class, "has a method reserved"),
                arguments(RoleFixtures.RepositoryWithoutRole.class, "carries 0 roles"),
                arguments(RoleFixtures.PropertiesWithoutRole.class, "carries 0 roles"));
    }

    @Test
    void anApiComponentReservedToTheApiIsAccepted() {
        ArchitectureRules.apiComponentsAreReservedToTheApi(ROOT)
                .check(new ClassFileImporter().importClasses(ApiFixtures.Reserved.class));
    }

    @Test
    void anApiComponentNotReservedToTheApiIsRejected() {
        assertRejected(
                ArchitectureRules.apiComponentsAreReservedToTheApi(ROOT),
                ApiFixtures.SharedController.class);
    }

    @Test
    void sharedAndWorkerComponentsWithoutApiResourcesAreAccepted() {
        var classes =
                new ClassFileImporter()
                        .importClasses(
                                RoleFixtures.Shared.class,
                                RoleFixtures.SharedWithPlainDependency.class,
                                RoleFixtures.WorkerWithWorkerDependency.class);
        ArchitectureRules.sharedComponentsAreFreeOfApiResources(ROOT).check(classes);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("sharedComponentsUsingApiResources")
    void sharedAndWorkerComponentsUsingApiResourcesAreRejected(
            Class<?> fixture, String violation) {
        assertRejectedFor(
                ArchitectureRules.sharedComponentsAreFreeOfApiResources(ROOT), fixture, violation);
    }

    static Stream<Arguments> sharedComponentsUsingApiResources() {
        return Stream.of(
                arguments(RoleFixtures.SharedWithRedis.class, "RedisTemplate"),
                arguments(RoleFixtures.WorkerWithRedis.class, "RedisTemplate"),
                arguments(RoleFixtures.SharedWithSession.class, "HttpSession"),
                arguments(RoleFixtures.SharedWithAControllerAnnotation.class, "RestController"),
                arguments(RoleFixtures.SharedWithAnApiClass.class, "ReservedToTheApi"),
                arguments(RoleFixtures.WorkerWithAnApiClass.class, "ReservedToTheApi"),
                arguments(ApiFixtures.SharedController.class, "is shared but in an api package"));
    }

    @Test
    void aRuleWithoutAnyClassToCheckIsRejected() {
        var rules =
                Stream.concat(
                        ArchitectureRules.all(ROOT).stream(),
                        Stream.of(
                                ArchitectureRules.routesDeclareTheirOwnResponses(),
                                ArchitectureRules.passwordEncoderOnlyIn(),
                                ArchitectureRules.withoutDatabaseNorTransaction(),
                                ArchitectureRules.transactionalMethodsDoNotWaitForRedis("", ""),
                                ArchitectureRules.methodIsOutsideATransaction("", ""),
                                ArchitectureRules.redisKeysWithoutPlainDigest(),
                                ArchitectureRules.throwsNoApplicationException(ROOT),
                                ArchitectureRules.componentsDeclareTheirRole(ROOT),
                                ArchitectureRules.apiComponentsAreReservedToTheApi(ROOT),
                                ArchitectureRules.sharedComponentsAreFreeOfApiResources(ROOT)));
        assertAll(
                rules.map(
                        rule ->
                                () ->
                                        assertChecksNothing(
                                                rule, new ClassFileImporter().importClasses())));
    }

    @Test
    void aLayerRuleWithoutItsLayerIsRejected() {
        var application = new ClassFileImporter().importClasses(ApplicationFixtures.Allowed.class);
        var api = new ClassFileImporter().importClasses(ApiFixtures.Allowed.class);
        assertAll(
                () -> assertChecksNothing(ArchitectureRules.apiBoundaries(ROOT), application),
                () -> assertChecksNothing(ArchitectureRules.applicationBoundaries(ROOT), api),
                () -> assertChecksNothing(ArchitectureRules.domainBoundaries(ROOT), application),
                () ->
                        assertChecksNothing(
                                ArchitectureRules.infrastructureBoundaries(ROOT), application),
                () ->
                        assertChecksNothing(
                                ArchitectureRules.configurationBoundaries(ROOT), application));
    }

    @Test
    void domainCannotMapToPersistenceIncludingByAnnotations() {
        var rule = ArchitectureRules.domainIsFreeOfPersistence(ROOT);
        assertRejected(rule, DomainFixtures.MappedEntity.class);
        assertRejected(rule, DomainFixtures.WithPersistenceAnnotation.class);
        rule.check(new ClassFileImporter().importClasses(DomainFixtures.Offer.class));
    }

    @Test
    void applicationCannotUseInfrastructureSpringDataOrJpa() {
        var rule = ArchitectureRules.applicationIsFreeOfInfrastructureSpringDataOrJpa(ROOT);
        assertRejected(rule, ApplicationFixtures.WithInfrastructure.class);
        assertRejected(rule, ApplicationFixtures.WithSpringData.class);
        assertRejected(rule, ApplicationFixtures.WithJpa.class);
        rule.check(new ClassFileImporter().importClasses(ApplicationFixtures.Allowed.class));
    }

    @Test
    void jpaEntityIsNamedJpaEntityAndLivesInInfrastructure() {
        var rule = ArchitectureRules.jpaEntitiesAreJpaEntitiesInInfrastructure(ROOT);
        rule.check(
                new ClassFileImporter()
                        .importClasses(InfrastructureFixtures.OfferJpaEntity.class));
        assertRejected(rule, DomainFixtures.MappedEntity.class);
        assertRejected(rule, ApplicationFixtures.WithEntity.class);
        assertRejected(rule, InfrastructureFixtures.OfferRow.class);
    }

    @Test
    void conventionalNamesAreAccepted() {
        ArchitectureRules.namesFollowConventions(ROOT)
                .check(
                        new ClassFileImporter()
                                .importClasses(
                                        NamingFixtures.OfferUseCase.class,
                                        NamingFixtures.OfferUnavailableException.class,
                                        NamingFixtures.Identity.class,
                                        InfrastructureFixtures.SpringDataOfferRepository.class,
                                        ApiFixtures.SharedController.class,
                                        ConfigurationFixtures.ClockConfiguration.class,
                                        ConfigurationFixtures.LimitProperties.class));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("misnamedClasses")
    void misnamedClassesAreRejected(Class<?> fixture, String violation) {
        assertRejectedFor(ArchitectureRules.namesFollowConventions(ROOT), fixture, violation);
    }

    static Stream<Arguments> misnamedClasses() {
        return Stream.of(
                arguments(NamingFixtures.OfferHelper.class, "contains the word Helper"),
                arguments(NamingFixtures.OfferManager.class, "contains the word Manager"),
                arguments(NamingFixtures.OfferImpl.class, "contains the word Impl"),
                arguments(NamingFixtures.OfferInfo.class, "contains the word Info"),
                arguments(NamingFixtures.OfferData.class, "contains the word Data"),
                arguments(NamingFixtures.OfferService.class, "contains the word Service"),
                arguments(NamingFixtures.IOffer.class, "is an interface with an I prefix"),
                arguments(ApiFixtures.Endpoint.class, "is a @RestController not named"),
                arguments(InfrastructureFixtures.Wiring.class, "is a @Configuration not named"),
                arguments(NamingFixtures.Limits.class, "is a @ConfigurationProperties not named"),
                arguments(NamingFixtures.Refusal.class, "is a Throwable not named"),
                arguments(
                        InfrastructureFixtures.OfferUseCase.class,
                        "is named with the suffix UseCase outside the application"));
    }

    private static void assertChecksNothing(ArchRule rule, JavaClasses classes) {
        var error = assertThrows(AssertionError.class, () -> rule.check(classes));
        assertTrue(
                error.getMessage().contains("failed to check any classes"),
                () -> rule.getDescription() + " should fail for lack of classes: " + error);
    }

    private static void assertRejectedFor(ArchRule rule, Class<?> fixture, String violation) {
        var classes = new ClassFileImporter().importClasses(fixture);
        assertTrue(
                rule.evaluate(classes).getFailureReport().getDetails().stream()
                        .anyMatch(detail -> detail.contains(violation)),
                () ->
                        rule.getDescription()
                                + " should reject "
                                + fixture.getName()
                                + " for "
                                + violation);
    }

    private static void assertRejected(ArchRule rule, Class<?> fixture) {
        var classes = new ClassFileImporter().importClasses(fixture);
        assertTrue(
                rule.evaluate(classes).hasViolation(),
                () -> rule.getDescription() + " should reject " + fixture.getName());
    }
}
