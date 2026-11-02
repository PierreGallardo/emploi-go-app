package fr.emploigo.auth.infrastructure.quota;

import fr.emploigo.auth.application.quota.QuotaScope;

import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Spring silently ignores environment variables that bind no property, so a typo would keep the
 * default quota.
 */
class QuotaEnvironmentCheck {
    QuotaEnvironmentCheck(ConfigurableEnvironment environment) {
        var known = known();
        environment.getPropertySources().stream()
                .filter(SystemEnvironmentPropertySource.class::isInstance)
                .map(EnumerablePropertySource.class::cast)
                .flatMap(source -> Arrays.stream(source.getPropertyNames()))
                // Case does not matter to Spring, so a typo in lower case is a typo all the same.
                .filter(
                        name ->
                                name.toUpperCase(Locale.ROOT)
                                        .startsWith(QuotaProperties.VARIABLE_PREFIX))
                .filter(name -> !known.contains(name.toUpperCase(Locale.ROOT)))
                .sorted()
                .findFirst()
                .ifPresent(
                        name -> {
                            throw new IllegalStateException(
                                    "La variable d'environnement "
                                            + name
                                            + " ne correspond à aucun "
                                            + "quota : elle serait ignorée. Écrire par exemple "
                                            + QuotaProperties.variable(
                                                    "scopes.registration-mail.identity-limit")
                                            + " (la clé de l'action en majuscules, ses tirets remplacés par des tirets bas).");
                        });
    }

    private static Set<String> known() {
        var fields =
                Arrays.stream(QuotaProperties.ScopeQuota.class.getRecordComponents())
                        .map(component -> QuotaProperties.kebab(component.getName()))
                        .toList();
        var delayFields =
                Arrays.stream(QuotaProperties.FailureDelay.class.getRecordComponents())
                        .map(component -> QuotaProperties.kebab(component.getName()))
                        .toList();
        var scopes =
                Arrays.stream(QuotaScope.values())
                        .flatMap(
                                scope ->
                                        Stream.concat(
                                                fields.stream()
                                                        .map(
                                                                field ->
                                                                        QuotaProperties.variable(
                                                                                "scopes."
                                                                                        + scope
                                                                                                .key()
                                                                                        + "."
                                                                                        + field)),
                                                delayFields.stream()
                                                        .map(
                                                                field ->
                                                                        QuotaProperties.variable(
                                                                                "scopes."
                                                                                        + scope
                                                                                                .key()
                                                                                        + ".failure-delay."
                                                                                        + field))));
        return scopes.collect(Collectors.toSet());
    }
}
