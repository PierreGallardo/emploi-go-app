package fr.emploigo.auth.infrastructure.quota;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import fr.emploigo.auth.application.quota.IdentityLimits;
import fr.emploigo.auth.application.quota.LoginLimits;
import fr.emploigo.auth.application.quota.LoginLimits.FailureDelay;
import fr.emploigo.auth.application.quota.QuotaLimits;
import fr.emploigo.auth.application.quota.QuotaLimits.MailLimits;
import fr.emploigo.auth.application.quota.QuotaScope;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.time.Duration;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;

class QuotaPropertiesTest {
    private static final String PREFIX = "emploi-go.auth.quota.";
    private static final Duration WINDOW = Duration.ofMinutes(15);

    /** The production configuration, without the real environment of the machine. */
    private final ApplicationContextRunner production =
            new ApplicationContextRunner()
                    .withUserConfiguration(QuotaConfiguration.class)
                    .withInitializer(new ConfigDataApplicationContextInitializer())
                    .withInitializer(
                            context ->
                                    context.getEnvironment()
                                            .getPropertySources()
                                            .remove(
                                                    StandardEnvironment
                                                            .SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME));

    private ApplicationContextRunner withVariables(Map<String, Object> variables) {
        return production.withInitializer(
                context ->
                        context.getEnvironment()
                                .getPropertySources()
                                .addFirst(
                                        new SystemEnvironmentPropertySource(
                                                "systemEnvironment-override", variables)));
    }

    private static QuotaLimits limits(int address, int identity, MailLimits mail) {
        return new QuotaLimits(address, identity, WINDOW, mail);
    }

    private static MailLimits mail() {
        return new MailLimits(Duration.ofSeconds(60), 300, 120);
    }

    static Stream<Arguments> defaults() {
        return Stream.of(
                argumentSet(
                        "registration-mail", QuotaScope.REGISTRATION_MAIL, limits(40, 3, mail())),
                argumentSet("confirmation", QuotaScope.CONFIRMATION, limits(60, 30, null)),
                argumentSet("recovery-mail", QuotaScope.RECOVERY_MAIL, limits(20, 3, mail())),
                argumentSet("recovery-confirm", QuotaScope.RECOVERY_CONFIRM, limits(30, 30, null)),
                argumentSet("google-start", QuotaScope.GOOGLE_START, limits(30, 10, null)),
                argumentSet("google-callback", QuotaScope.GOOGLE_CALLBACK, limits(30, 10, null)),
                argumentSet("google-complete", QuotaScope.GOOGLE_COMPLETE, limits(30, 10, null)),
                argumentSet("google-link", QuotaScope.GOOGLE_LINK, limits(30, 10, null)),
                argumentSet("google-mail", QuotaScope.GOOGLE_MAIL, limits(10, 3, mail())),
                argumentSet(
                        "google-email-confirm",
                        QuotaScope.GOOGLE_EMAIL_CONFIRM,
                        limits(30, 10, null)));
    }

    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("defaults")
    void productionConfigurationKeepsTheLimitsThatWereWrittenInTheCode(
            QuotaScope scope, QuotaLimits expected) {
        production.run(
                context ->
                        assertThat(context.getBean(QuotaProperties.class).limits(scope))
                                .as("limits")
                                .isEqualTo(expected));
    }

    @Test
    void productionConfigurationGivesTheLoginItsAddressLimitAndItsDelayAfterFailures() {
        production.run(
                context ->
                        assertThat(context.getBean(QuotaProperties.class).loginLimits())
                                .as("login limits")
                                .isEqualTo(
                                        new LoginLimits(
                                                90,
                                                WINDOW,
                                                new FailureDelay(
                                                        5,
                                                        Duration.ofSeconds(5),
                                                        2.0,
                                                        Duration.ofMinutes(5),
                                                        Duration.ofMinutes(30)))));
    }

    static Stream<Arguments> missingLimits() {
        return Stream.of(
                argumentSet(
                        "login sans limite générale",
                        (Function<QuotaProperties, Object>) q -> q.limits(QuotaScope.LOGIN),
                        "login"),
                argumentSet(
                        "reconnexion sans limite par adresse",
                        (Function<QuotaProperties, Object>) q -> q.limits(QuotaScope.RESUME),
                        "resume"),
                argumentSet(
                        "action avec adresse sans limite d'identité seule",
                        (Function<QuotaProperties, Object>)
                                q -> q.identityLimits(QuotaScope.GOOGLE_START),
                        "google-start"));
    }

    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("missingLimits")
    void readingALimitTheActionLacksIsRefusedAndNamesIt(
            Function<QuotaProperties, Object> read, String action) {
        production.run(
                context ->
                        assertThatThrownBy(
                                        () -> read.apply(context.getBean(QuotaProperties.class)))
                                .as("limits")
                                .isInstanceOf(IllegalArgumentException.class)
                                .hasMessageContaining(action));
    }

    @Test
    void productionConfigurationLimitsTheReconnectionByFamilyAndNotByAddress() {
        production.run(
                context ->
                        assertThat(
                                        context.getBean(QuotaProperties.class)
                                                .identityLimits(QuotaScope.RESUME))
                                .as("limits of the reconnection")
                                .isEqualTo(new IdentityLimits(10, WINDOW)));
    }

    @Test
    void environmentVariableOverridesTheFamilyLimitOfTheReconnection() {
        withVariables(Map.of("EMPLOI_GO_AUTH_QUOTA_SCOPES_RESUME_IDENTITY_LIMIT", "77"))
                .run(
                        context ->
                                assertThat(
                                                context.getBean(QuotaProperties.class)
                                                        .identityLimits(QuotaScope.RESUME)
                                                        .limit())
                                        .as("family limit")
                                        .isEqualTo(77));
    }

    @Test
    void productionConfigurationBindsEveryScopeByItsKey() {
        production.run(
                context ->
                        assertThat(context.getBean(QuotaProperties.class).scopes().keySet())
                                .as("scopes")
                                .containsExactlyInAnyOrder(QuotaScope.values()));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(
            value = QuotaScope.class,
            mode = EnumSource.Mode.EXCLUDE,
            names = {"LOGIN", "RESUME"})
    void environmentVariableOverridesTheIdentityLimitOfEveryScopeAndOnlyIt(QuotaScope scope) {
        withVariables(
                        Map.of(
                                "EMPLOI_GO_AUTH_QUOTA_SCOPES_" + scope.name() + "_IDENTITY_LIMIT",
                                "77"))
                .run(
                        context -> {
                            var quotas = context.getBean(QuotaProperties.class);
                            assertThat(quotas.limits(scope).identityLimit())
                                    .as("identity limit")
                                    .isEqualTo(77);
                            // The address limit of every action in the files is 10, 20, 30, 40 or
                            // 60.
                            assertThat(quotas.limits(scope).addressLimit())
                                    .as("address limit kept")
                                    .isIn(10, 20, 30, 40, 60);
                        });
    }

    static Stream<Arguments> validOverrides() {
        return Stream.of(
                argumentSet(
                        "limite d'adresse",
                        "SCOPES_REGISTRATION_MAIL_ADDRESS_LIMIT",
                        "4",
                        (Function<QuotaProperties, Object>)
                                q -> q.limits(QuotaScope.REGISTRATION_MAIL).addressLimit(),
                        4),
                argumentSet(
                        "limite d'identité",
                        "SCOPES_REGISTRATION_MAIL_IDENTITY_LIMIT",
                        "5",
                        (Function<QuotaProperties, Object>)
                                q -> q.limits(QuotaScope.REGISTRATION_MAIL).identityLimit(),
                        5),
                argumentSet(
                        "limite du préfixe",
                        "SCOPES_REGISTRATION_MAIL_PREFIX_LIMIT",
                        "120",
                        (Function<QuotaProperties, Object>)
                                q -> q.limits(QuotaScope.REGISTRATION_MAIL).mail().prefixLimit(),
                        120),
                argumentSet(
                        "fenêtre",
                        "SCOPES_REGISTRATION_MAIL_WINDOW",
                        "20m",
                        (Function<QuotaProperties, Object>)
                                q -> q.limits(QuotaScope.REGISTRATION_MAIL).window(),
                        Duration.ofMinutes(20)),
                argumentSet(
                        "délai entre deux envois",
                        "SCOPES_REGISTRATION_MAIL_SEND_DELAY",
                        "2m",
                        (Function<QuotaProperties, Object>)
                                q -> q.limits(QuotaScope.REGISTRATION_MAIL).mail().sendDelay(),
                        Duration.ofMinutes(2)),
                argumentSet(
                        "seuil d'alerte global",
                        "SCOPES_GOOGLE_MAIL_GLOBAL_ALERT_THRESHOLD",
                        "900",
                        (Function<QuotaProperties, Object>)
                                q -> q.limits(QuotaScope.GOOGLE_MAIL).mail().globalAlertThreshold(),
                        900),
                argumentSet(
                        "échecs libres de la connexion",
                        "SCOPES_LOGIN_FAILURE_DELAY_FREE_FAILURES",
                        "3",
                        (Function<QuotaProperties, Object>)
                                q -> q.loginLimits().delay().freeFailures(),
                        3),
                argumentSet(
                        "délai de base de la connexion",
                        "SCOPES_LOGIN_FAILURE_DELAY_BASE_DELAY",
                        "10s",
                        (Function<QuotaProperties, Object>)
                                q -> q.loginLimits().delay().baseDelay(),
                        Duration.ofSeconds(10)),
                argumentSet(
                        "croissance du délai de la connexion",
                        "SCOPES_LOGIN_FAILURE_DELAY_GROWTH_FACTOR",
                        "1.5",
                        (Function<QuotaProperties, Object>)
                                q -> q.loginLimits().delay().growthFactor(),
                        1.5),
                argumentSet(
                        "plafond du délai de la connexion",
                        "SCOPES_LOGIN_FAILURE_DELAY_MAX_DELAY",
                        "2m",
                        (Function<QuotaProperties, Object>) q -> q.loginLimits().delay().maxDelay(),
                        Duration.ofMinutes(2)),
                argumentSet(
                        "vie du compteur d'échecs",
                        "SCOPES_LOGIN_FAILURE_DELAY_COUNTER_TTL",
                        "45m",
                        (Function<QuotaProperties, Object>)
                                q -> q.loginLimits().delay().counterTtl(),
                        Duration.ofMinutes(45)),
                argumentSet(
                        "fenêtre de l'adresse de la connexion",
                        "SCOPES_LOGIN_WINDOW",
                        "10m",
                        (Function<QuotaProperties, Object>) q -> q.loginLimits().window(),
                        Duration.ofMinutes(10)));
    }

    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("validOverrides")
    void environmentVariableOverridesEachKindOfProperty(
            String variable,
            String value,
            Function<QuotaProperties, Object> read,
            Object expected) {
        withVariables(Map.of("EMPLOI_GO_AUTH_QUOTA_" + variable, value))
                .run(
                        context ->
                                assertThat(read.apply(context.getBean(QuotaProperties.class)))
                                        .as("value")
                                        .isEqualTo(expected));
    }

    @Test
    void propertyOverridesTheValueOfTheFile() {
        production
                .withPropertyValues(PREFIX + "scopes.google-mail.global-alert-threshold=900")
                .run(
                        context ->
                                assertThat(
                                                context.getBean(QuotaProperties.class)
                                                        .limits(QuotaScope.GOOGLE_MAIL)
                                                        .mail())
                                        .as("mail limits")
                                        .isEqualTo(
                                                new MailLimits(Duration.ofSeconds(60), 900, 120)));
    }

    static Stream<Arguments> durationsWithoutUnit() {
        return Stream.of(
                argumentSet(
                        "fenêtre",
                        "scopes.google-start.window=900",
                        (Function<QuotaProperties, Object>)
                                q -> q.limits(QuotaScope.GOOGLE_START).window(),
                        Duration.ofMinutes(15)),
                argumentSet(
                        "fenêtre d'un jour",
                        "scopes.google-start.window=86400",
                        (Function<QuotaProperties, Object>)
                                q -> q.limits(QuotaScope.GOOGLE_START).window(),
                        Duration.ofDays(1)),
                argumentSet(
                        "fenêtre de la connexion",
                        "scopes.login.window=600",
                        (Function<QuotaProperties, Object>) q -> q.loginLimits().window(),
                        Duration.ofMinutes(10)),
                argumentSet(
                        "délai entre deux envois",
                        "scopes.registration-mail.send-delay=90",
                        (Function<QuotaProperties, Object>)
                                q -> q.limits(QuotaScope.REGISTRATION_MAIL).mail().sendDelay(),
                        Duration.ofSeconds(90)),
                argumentSet(
                        "délai de base de la connexion",
                        "scopes.login.failure-delay.base-delay=7",
                        (Function<QuotaProperties, Object>)
                                q -> q.loginLimits().delay().baseDelay(),
                        Duration.ofSeconds(7)),
                argumentSet(
                        "plafond du délai de la connexion",
                        "scopes.login.failure-delay.max-delay=120",
                        (Function<QuotaProperties, Object>) q -> q.loginLimits().delay().maxDelay(),
                        Duration.ofSeconds(120)),
                argumentSet(
                        "vie du compteur d'échecs",
                        "scopes.login.failure-delay.counter-ttl=3000",
                        (Function<QuotaProperties, Object>)
                                q -> q.loginLimits().delay().counterTtl(),
                        Duration.ofMinutes(50)));
    }

    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("durationsWithoutUnit")
    void durationWithoutUnitIsInSecondsNotInMilliseconds(
            String property, Function<QuotaProperties, Object> read, Object expected) {
        production
                .withPropertyValues(PREFIX + property)
                .run(
                        context ->
                                assertThat(read.apply(context.getBean(QuotaProperties.class)))
                                        .as("value")
                                        .isEqualTo(expected));
    }

    static Stream<Arguments> mistypedProperties() {
        return Stream.of(
                argumentSet(
                        "mode de panne retiré",
                        PREFIX + "scopes.google-start.failure-mode=open",
                        "scopes.google-start.failure-mode"),
                argumentSet(
                        "limite d'adresse",
                        PREFIX + "scopes.login.adress-limit=99",
                        "scopes.login.adress-limit"),
                argumentSet("scope inconnu", PREFIX + "scopes.logon.address-limit=5", "logon"),
                argumentSet(
                        "délai inconnu",
                        PREFIX + "scopes.login.failure-delay.base-delai=3",
                        "base-delai"));
    }

    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("mistypedProperties")
    void unknownPropertyRefusesTheStartupAndNamesItSoItIsNeverIgnored(
            String property, String name) {
        production
                .withPropertyValues(property)
                .run(
                        context ->
                                assertThat(context)
                                        .as("context")
                                        .hasFailed()
                                        .getFailure()
                                        .hasStackTraceContaining(name));
    }

    static Stream<Arguments> mistypedVariables() {
        return Stream.of(
                argumentSet(
                        "tiret supprimé",
                        "EMPLOI_GO_AUTH_QUOTA_SCOPES_REGISTRATIONMAIL_IDENTITY_LIMIT"),
                argumentSet("champ inconnu", "EMPLOI_GO_AUTH_QUOTA_SCOPES_LOGIN_ADRESS_LIMIT"),
                argumentSet("scope inconnu", "EMPLOI_GO_AUTH_QUOTA_SCOPES_LOGON_ADDRESS_LIMIT"),
                argumentSet(
                        "tirets conservés",
                        "EMPLOI_GO_AUTH_QUOTA_SCOPES_REGISTRATION-MAIL_IDENTITY_LIMIT"),
                argumentSet(
                        "champ du délai inconnu",
                        "EMPLOI_GO_AUTH_QUOTA_SCOPES_LOGIN_FAILURE_DELAY_MAXDELAY"),
                argumentSet(
                        "mode de panne retiré",
                        "EMPLOI_GO_AUTH_QUOTA_SCOPES_GOOGLE_START_FAILURE_MODE"),
                argumentSet("en minuscules", "emploi_go_auth_quota_scopes_login_adress_limit"));
    }

    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("mistypedVariables")
    void environmentVariableThatBindsNothingRefusesTheStartupAndNamesItself(String variable) {
        withVariables(Map.of(variable, "5"))
                .run(
                        context ->
                                assertThat(context)
                                        .as("context")
                                        .hasFailed()
                                        .getFailure()
                                        .hasStackTraceContaining(variable)
                                        .hasStackTraceContaining("EMPLOI_GO_AUTH_QUOTA_SCOPES_"));
    }

    @Test
    void unrelatedEnvironmentVariableIsNotLookedAt() {
        withVariables(Map.of("EMPLOI_GO_AUTH_DEVELOPMENT_ACCOUNT", "true", "PATH", "/usr/bin"))
                .run(context -> assertThat(context).as("context").hasNotFailed());
    }

    static Stream<Arguments> refusals() {
        return Stream.of(
                argumentSet(
                        "limite d'adresse absente",
                        "scopes.login.address-limit=",
                        "scopes.login.address-limit"),
                argumentSet(
                        "limite d'adresse nulle",
                        "scopes.login.address-limit=0",
                        "scopes.login.address-limit"),
                argumentSet(
                        "limite d'adresse énorme",
                        "scopes.login.address-limit=10001",
                        "scopes.login.address-limit"),
                argumentSet(
                        "limite d'identité absente",
                        "scopes.google-start.identity-limit=",
                        "scopes.google-start.identity-limit"),
                argumentSet(
                        "limite d'identité nulle",
                        "scopes.google-start.identity-limit=0",
                        "scopes.google-start.identity-limit"),
                argumentSet(
                        "limite d'identité sur la connexion",
                        "scopes.login.identity-limit=10",
                        "scopes.login.identity-limit"),
                argumentSet(
                        "limite d'adresse sur la reprise",
                        "scopes.resume.address-limit=30",
                        "scopes.resume.address-limit"),
                argumentSet(
                        "limite de famille nulle",
                        "scopes.resume.identity-limit=0",
                        "scopes.resume.identity-limit"),
                argumentSet(
                        "limite de famille absente",
                        "scopes.resume.identity-limit=",
                        "scopes.resume.identity-limit"),
                argumentSet(
                        "délai sur une action sans échecs comptés",
                        "scopes.google-start.failure-delay.max-delay=5m",
                        "scopes.google-start.failure-delay"),
                argumentSet(
                        "échecs libres nuls",
                        "scopes.login.failure-delay.free-failures=0",
                        "scopes.login.failure-delay.free-failures"),
                argumentSet(
                        "échecs libres énormes",
                        "scopes.login.failure-delay.free-failures=101",
                        "scopes.login.failure-delay.free-failures"),
                argumentSet(
                        "échecs libres absents",
                        "scopes.login.failure-delay.free-failures=",
                        "scopes.login.failure-delay.free-failures"),
                argumentSet(
                        "délai de base trop court",
                        "scopes.login.failure-delay.base-delay=500ms",
                        "scopes.login.failure-delay.base-delay"),
                argumentSet(
                        "délai de base trop long",
                        "scopes.login.failure-delay.base-delay=6m",
                        "scopes.login.failure-delay.base-delay"),
                argumentSet(
                        "croissance inférieure à 1",
                        "scopes.login.failure-delay.growth-factor=0.9",
                        "scopes.login.failure-delay.growth-factor"),
                argumentSet(
                        "croissance énorme",
                        "scopes.login.failure-delay.growth-factor=10.5",
                        "scopes.login.failure-delay.growth-factor"),
                argumentSet(
                        "plafond trop long",
                        "scopes.login.failure-delay.max-delay=2h",
                        "scopes.login.failure-delay.max-delay"),
                argumentSet(
                        "plafond sous le délai de base",
                        "scopes.login.failure-delay.max-delay=4s",
                        "scopes.login.failure-delay.max-delay"),
                argumentSet(
                        "vie du compteur trop courte",
                        "scopes.login.failure-delay.counter-ttl=30s",
                        "scopes.login.failure-delay.counter-ttl"),
                argumentSet(
                        "vie du compteur égale au plafond",
                        "scopes.login.failure-delay.counter-ttl=5m",
                        "scopes.login.failure-delay.counter-ttl"),
                argumentSet(
                        "vie du compteur trop longue",
                        "scopes.login.failure-delay.counter-ttl=25h",
                        "scopes.login.failure-delay.counter-ttl"),
                argumentSet(
                        "vie du compteur au-delà de quatre fenêtres",
                        "scopes.login.failure-delay.counter-ttl=61m",
                        "scopes.login.failure-delay.counter-ttl"),
                argumentSet(
                        "fenêtre qui rend la vie du compteur trop longue",
                        "scopes.login.window=5m",
                        "scopes.login.failure-delay.counter-ttl"),
                argumentSet("fenêtre absente", "scopes.login.window=", "scopes.login.window"),
                argumentSet(
                        "fenêtre trop courte", "scopes.login.window=30s", "scopes.login.window"),
                argumentSet(
                        "fenêtre trop longue", "scopes.login.window=25h", "scopes.login.window"),
                argumentSet(
                        "fenêtre illisible", "scopes.login.window=bientôt", "scopes.login.window"),
                argumentSet(
                        "délai d'envoi absent",
                        "scopes.registration-mail.send-delay=",
                        "scopes.registration-mail.send-delay"),
                argumentSet(
                        "délai d'envoi trop court",
                        "scopes.registration-mail.send-delay=500ms",
                        "scopes.registration-mail.send-delay"),
                argumentSet(
                        "délai d'envoi trop long",
                        "scopes.registration-mail.send-delay=2h",
                        "scopes.registration-mail.send-delay"),
                argumentSet(
                        "seuil global absent",
                        "scopes.recovery-mail.global-alert-threshold=",
                        "scopes.recovery-mail.global-alert-threshold"),
                argumentSet(
                        "seuil global nul",
                        "scopes.recovery-mail.global-alert-threshold=0",
                        "scopes.recovery-mail.global-alert-threshold"),
                argumentSet(
                        "seuil global énorme",
                        "scopes.recovery-mail.global-alert-threshold=1000001",
                        "scopes.recovery-mail.global-alert-threshold"),
                argumentSet(
                        "limite du préfixe absente",
                        "scopes.registration-mail.prefix-limit=",
                        "scopes.registration-mail.prefix-limit"),
                argumentSet(
                        "limite du préfixe nulle",
                        "scopes.registration-mail.prefix-limit=0",
                        "scopes.registration-mail.prefix-limit"),
                argumentSet(
                        "limite du préfixe énorme",
                        "scopes.registration-mail.prefix-limit=10001",
                        "scopes.registration-mail.prefix-limit"),
                argumentSet(
                        "limite du préfixe sous celle de l'adresse",
                        "scopes.registration-mail.prefix-limit=39",
                        "scopes.registration-mail.prefix-limit"),
                argumentSet(
                        "limite du préfixe sur une action sans email",
                        "scopes.confirmation.prefix-limit=60",
                        "scopes.confirmation.prefix-limit"),
                argumentSet(
                        "délai sur une action sans email",
                        "scopes.login.send-delay=60s",
                        "scopes.login.send-delay"),
                argumentSet(
                        "seuil sur une action sans email",
                        "scopes.login.global-alert-threshold=300",
                        "scopes.login.global-alert-threshold"));
    }

    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("refusals")
    void invalidConfigurationRefusesTheStartupAndNamesTheProperty(String property, String name) {
        production
                .withPropertyValues(PREFIX + property)
                .run(
                        context ->
                                assertThat(context)
                                        .as("context")
                                        .hasFailed()
                                        .getFailure()
                                        .hasStackTraceContaining(PREFIX + name));
    }

    static Stream<Arguments> boundaries() {
        return Stream.of(
                argumentSet("limite d'adresse minimale", "scopes.login.address-limit=1"),
                argumentSet("limite d'adresse maximale", "scopes.login.address-limit=10000"),
                argumentSet(
                        "limite du préfixe égale à celle de l'adresse",
                        "scopes.registration-mail.prefix-limit=40"),
                argumentSet("limite d'identité minimale", "scopes.google-start.identity-limit=1"),
                argumentSet(
                        "limite d'identité maximale", "scopes.google-start.identity-limit=10000"),
                argumentSet("échecs libres minimaux", "scopes.login.failure-delay.free-failures=1"),
                argumentSet(
                        "échecs libres maximaux", "scopes.login.failure-delay.free-failures=100"),
                argumentSet("délai de base minimal", "scopes.login.failure-delay.base-delay=1s"),
                argumentSet("délai de base maximal", "scopes.login.failure-delay.base-delay=5m"),
                argumentSet("croissance minimale", "scopes.login.failure-delay.growth-factor=1"),
                argumentSet("croissance maximale", "scopes.login.failure-delay.growth-factor=10"),
                argumentSet(
                        "plafond égal au délai de base", "scopes.login.failure-delay.max-delay=5s"),
                argumentSet(
                        "vie du compteur minimale", "scopes.login.failure-delay.counter-ttl=6m"),
                argumentSet(
                        "vie du compteur à quatre fenêtres",
                        "scopes.login.failure-delay.counter-ttl=1h"),
                argumentSet("fenêtre minimale", "scopes.google-start.window=1m"),
                argumentSet("fenêtre maximale", "scopes.login.window=24h"),
                argumentSet("délai d'envoi minimal", "scopes.registration-mail.send-delay=1s"),
                argumentSet("délai d'envoi maximal", "scopes.registration-mail.send-delay=1h"),
                argumentSet(
                        "seuil global minimal", "scopes.recovery-mail.global-alert-threshold=1"),
                argumentSet(
                        "seuil global maximal",
                        "scopes.recovery-mail.global-alert-threshold=1000000"));
    }

    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("boundaries")
    void valueOnTheBoundOfTheRangeIsAccepted(String property) {
        production
                .withPropertyValues(PREFIX + property)
                .run(context -> assertThat(context).as("context").hasNotFailed());
    }

    @Test
    void loginWithoutItsDelayRefusesTheStartupAndNamesTheBlock() {
        new ApplicationContextRunner()
                .withUserConfiguration(QuotaConfiguration.class)
                .withPropertyValues(
                        PREFIX + "scopes.login.address-limit=30",
                        PREFIX + "scopes.login.window=15m")
                .run(
                        context ->
                                assertThat(context)
                                        .as("context")
                                        .hasFailed()
                                        .getFailure()
                                        .hasStackTraceContaining(
                                                PREFIX + "scopes.login.failure-delay"));
    }

    @Test
    void scopeThatTheConfigurationDoesNotMentionRefusesTheStartup() {
        new ApplicationContextRunner()
                .withUserConfiguration(QuotaConfiguration.class)
                .withPropertyValues(
                        PREFIX + "scopes.login.address-limit=30",
                        PREFIX + "scopes.login.window=15m",
                        PREFIX + "scopes.login.failure-delay.free-failures=5",
                        PREFIX + "scopes.login.failure-delay.base-delay=5s",
                        PREFIX + "scopes.login.failure-delay.growth-factor=2",
                        PREFIX + "scopes.login.failure-delay.max-delay=5m",
                        PREFIX + "scopes.login.failure-delay.counter-ttl=30m")
                .run(
                        context ->
                                assertThat(context)
                                        .as("context")
                                        .hasFailed()
                                        .getFailure()
                                        .hasStackTraceContaining(
                                                PREFIX + "scopes.registration-mail"));
    }
}
