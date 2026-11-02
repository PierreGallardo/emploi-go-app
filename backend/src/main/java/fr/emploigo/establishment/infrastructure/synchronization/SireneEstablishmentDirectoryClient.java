package fr.emploigo.establishment.infrastructure.synchronization;

import com.fasterxml.jackson.annotation.JsonProperty;

import fr.emploigo.config.BoundedHttpExchange;
import fr.emploigo.config.ExternalHttpHeaders;
import fr.emploigo.establishment.application.synchronization.EstablishmentDirectoryClient;
import fr.emploigo.establishment.domain.DirectoryEstablishment;
import fr.emploigo.establishment.domain.Position;
import fr.emploigo.establishment.domain.Siret;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The API Sirene of the INSEE (version 3.11): searches establishments directly, with an API key and
 * 30 requests per minute. Asks for 1 000 establishments per page, the most it gives, and walks the
 * pages with the cursor it returns.
 */
class SireneEstablishmentDirectoryClient implements EstablishmentDirectoryClient {
    // 30 requests per minute, with a margin for the clocks.
    static final Duration REQUEST_INTERVAL = Duration.ofMillis(2_100);
    static final int ATTEMPTS = 4;
    private static final int PAGE_SIZE = 1_000;
    private static final String FIRST_CURSOR = "*";
    private static final Duration FIRST_RETRY_DELAY = Duration.ofSeconds(1);
    private static final Duration MAX_RETRY_AFTER = Duration.ofMinutes(1);
    private static final int MAX_BODY_BYTES = 64 * 1024 * 1024;
    private static final List<String> OUTCOMES = List.of("ok", "rate-limited", "failed", "refused");
    // INSEE legal categories of natural persons and of groups of them, whose name is usually the
    // names of persons.
    private static final Set<String> PERSON_LEGAL_CATEGORIES =
            Set.of("1000", "2110", "2210", "2310");

    /** Waits between two requests; the tests replace it. */
    interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    record SearchResponse(
            @JsonProperty("header") HeaderJson header,
            @JsonProperty("etablissements") List<EstablishmentJson> establishments) {}

    record HeaderJson(
            @JsonProperty("total") Integer total,
            @JsonProperty("curseurSuivant") String nextCursor) {}

    record EstablishmentJson(
            @JsonProperty("siret") String siret,
            @JsonProperty("statutDiffusionEtablissement") String diffusion,
            @JsonProperty("trancheEffectifsEtablissement") String headcountRange,
            @JsonProperty("activitePrincipaleNAF25Etablissement") String activityNaf2025,
            @JsonProperty("uniteLegale") CompanyJson company,
            @JsonProperty("adresseEtablissement") AddressJson address,
            @JsonProperty("periodesEtablissement") List<PeriodJson> periods) {}

    record CompanyJson(
            @JsonProperty("statutDiffusionUniteLegale") String diffusion,
            @JsonProperty("dateCreationUniteLegale") String createdOn,
            @JsonProperty("categorieEntreprise") String category,
            @JsonProperty("denominationUniteLegale") String name,
            @JsonProperty("categorieJuridiqueUniteLegale") String legalCategory,
            @JsonProperty("economieSocialeSolidaireUniteLegale") String ess) {}

    record AddressJson(
            @JsonProperty("complementAdresseEtablissement") String complement,
            @JsonProperty("numeroVoieEtablissement") String number,
            @JsonProperty("indiceRepetitionEtablissement") String repetition,
            @JsonProperty("typeVoieEtablissement") String streetType,
            @JsonProperty("libelleVoieEtablissement") String street,
            @JsonProperty("codePostalEtablissement") String postalCode,
            @JsonProperty("libelleCommuneEtablissement") String commune,
            @JsonProperty("codeCommuneEtablissement") String communeCode,
            @JsonProperty("coordonneeLambertAbscisseEtablissement") String east,
            @JsonProperty("coordonneeLambertOrdonneeEtablissement") String north) {}

    /** A period of the history of the establishment; the current one has no end date. */
    record PeriodJson(
            @JsonProperty("dateFin") String endsOn,
            @JsonProperty("enseigne1Etablissement") String sign1,
            @JsonProperty("enseigne2Etablissement") String sign2,
            @JsonProperty("enseigne3Etablissement") String sign3,
            @JsonProperty("denominationUsuelleEtablissement") String tradeName,
            @JsonProperty("activitePrincipaleEtablissement") String activity,
            @JsonProperty("nomenclatureActivitePrincipaleEtablissement") String nomenclature) {}

    private final EstablishmentSynchronizationProperties properties;
    private final ObjectMapper json;
    private final Clock clock;
    private final Sleeper sleeper;
    private final HttpClient client;
    private final Map<String, Counter> requests = new LinkedHashMap<>();
    private Instant nextRequest = Instant.MIN;

    SireneEstablishmentDirectoryClient(
            EstablishmentSynchronizationProperties properties,
            ObjectMapper json,
            MeterRegistry meters,
            Clock clock,
            Sleeper sleeper) {
        this.properties = properties;
        this.json = json;
        this.clock = clock;
        this.sleeper = sleeper;
        this.client = HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).build();
        for (String outcome : OUTCOMES)
            requests.put(
                    outcome,
                    Counter.builder("establishment.directory.requests")
                            .tag("outcome", outcome)
                            .description("Requêtes à l'annuaire Sirene")
                            .register(meters));
    }

    @Override
    public Result read(List<String> communeCodes, String cursor) {
        if (properties.apiKey() == null)
            return new Result.Unavailable(
                    "clé d'API absente (emploi-go.establishment-synchronization.api-key)");
        String sent = cursor == null ? FIRST_CURSOR : cursor;
        HttpRequest request =
                HttpRequest.newBuilder(uri(communeCodes, sent))
                        .timeout(properties.responseTimeout())
                        .header("User-Agent", ExternalHttpHeaders.USER_AGENT)
                        .header("Accept", "application/json")
                        .header("X-INSEE-Api-Key-Integration", properties.apiKey())
                        .GET()
                        .build();
        Duration backoff = FIRST_RETRY_DELAY;
        Duration wait = Duration.ZERO;
        String cause = "aucune tentative";
        try {
            for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
                if (attempt > 1) sleeper.sleep(wait);
                wait = backoff;
                backoff = backoff.multipliedBy(2);
                pace();
                Optional<HttpResponse<byte[]>> answer =
                        BoundedHttpExchange.send(
                                client,
                                request,
                                HttpResponse.BodyHandlers.limiting(
                                        HttpResponse.BodyHandlers.ofByteArray(), MAX_BODY_BYTES),
                                properties.connectTimeout().plus(properties.responseTimeout()));
                if (answer.isEmpty()) {
                    count("failed");
                    cause = "pas de réponse";
                    continue;
                }
                HttpResponse<byte[]> response = answer.get();
                int status = response.statusCode();
                if (status == 200) {
                    Optional<Result.Page> page = page(response.body(), sent);
                    if (page.isPresent()) {
                        count("ok");
                        return page.get();
                    }
                    // A gateway may answer 200 with an error page: retried like a failure.
                    count("failed");
                    cause = "réponse illisible";
                    continue;
                }
                if (status == 429) {
                    count("rate-limited");
                    cause = "limite de débit";
                    Optional<Duration> asked = retryAfter(response);
                    if (asked.isPresent() && asked.get().compareTo(MAX_RETRY_AFTER) > 0)
                        return new Result.Unavailable("attente demandée trop longue");
                    if (asked.isPresent() && asked.get().compareTo(wait) > 0) wait = asked.get();
                    continue;
                }
                if (status >= 500) {
                    count("failed");
                    cause = "statut " + status;
                    continue;
                }
                // The API answers 404 to a search that finds nothing.
                if (status == 404) {
                    count("ok");
                    return new Result.Page(0, null, List.of());
                }
                count("refused");
                return new Result.Unavailable("statut " + status);
            }
            return new Result.Unavailable(cause);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return new Result.Unavailable("interrompu");
        }
    }

    /** Spaces the requests out to stay under the rate limit of the directory. */
    private void pace() throws InterruptedException {
        Instant now = clock.instant();
        if (now.isBefore(nextRequest)) {
            sleeper.sleep(Duration.between(now, nextRequest));
            now = nextRequest;
        }
        nextRequest = now.plus(REQUEST_INTERVAL);
    }

    /** The page, or empty when the body is not the expected answer. */
    private Optional<Result.Page> page(byte[] body, String sentCursor) {
        SearchResponse response;
        try {
            response = json.readValue(body, SearchResponse.class);
        } catch (JacksonException error) {
            return Optional.empty();
        }
        if (response == null
                || response.header() == null
                || response.header().total() == null
                || response.establishments() == null) return Optional.empty();
        // An establishment that cannot be read is left out: the run sees fewer than announced.
        List<DirectoryEstablishment> establishments =
                response.establishments().stream()
                        .filter(Objects::nonNull)
                        .map(SireneEstablishmentDirectoryClient::establishment)
                        .flatMap(Optional::stream)
                        .toList();
        // The last page returns the cursor it was asked with.
        String next = response.header().nextCursor();
        boolean last =
                next == null || next.equals(sentCursor) || response.establishments().isEmpty();
        return Optional.of(
                new Result.Page(response.header().total(), last ? null : next, establishments));
    }

    private static Optional<DirectoryEstablishment> establishment(EstablishmentJson e) {
        Optional<Siret> siret = Siret.of(e.siret());
        Optional<PeriodJson> current =
                e.periods() == null
                        ? Optional.empty()
                        : e.periods().stream()
                                .filter(period -> period != null && period.endsOn() == null)
                                .findFirst();
        // Read as an opposition, a missing diffusion status would delete the establishment.
        if (siret.isEmpty()
                || current.isEmpty()
                || e.diffusion() == null
                || e.company() == null
                || e.company().diffusion() == null
                || e.address() == null) return Optional.empty();
        CompanyJson company = e.company();
        AddressJson address = e.address();
        PeriodJson period = current.get();
        // In doubt, the name of the company is taken for the name of a person.
        boolean namedAfterPerson =
                company.legalCategory() == null
                        || PERSON_LEGAL_CATEGORIES.contains(company.legalCategory());
        return Optional.of(
                new DirectoryEstablishment(
                        siret.get(),
                        "O".equals(company.diffusion()) && "O".equals(e.diffusion()),
                        namedAfterPerson,
                        company.name(),
                        Stream.of(period.sign1(), period.sign2(), period.sign3())
                                .filter(Objects::nonNull)
                                .toList(),
                        period.tradeName(),
                        postalAddress(address),
                        address.postalCode(),
                        address.communeCode(),
                        Position.parseLambert93(address.east(), address.north()).orElse(null),
                        "NAFRev2".equals(period.nomenclature()) ? period.activity() : null,
                        activityNaf2025(e, period),
                        e.headcountRange(),
                        company.category(),
                        date(company.createdOn()),
                        "O".equals(company.ess())));
    }

    /** The NAF 2025 code of the establishment, which the INSEE gives apart until 2027. */
    private static String activityNaf2025(EstablishmentJson e, PeriodJson period) {
        if (e.activityNaf2025() != null) return e.activityNaf2025();
        return "NAF2025".equals(period.nomenclature()) ? period.activity() : null;
    }

    /** The address as a line, from the pieces the INSEE gives; null without a street. */
    private static String postalAddress(AddressJson address) {
        if (address.street() == null || address.street().isBlank()) return null;
        String number =
                Stream.of(address.number(), address.repetition())
                        .filter(Objects::nonNull)
                        .collect(Collectors.joining());
        return Stream.of(
                        address.complement(),
                        number,
                        address.streetType(),
                        address.street(),
                        address.postalCode(),
                        address.commune())
                .filter(Objects::nonNull)
                .filter(part -> !part.isBlank())
                .collect(Collectors.joining(" "));
    }

    private URI uri(List<String> communeCodes, String cursor) {
        String communes =
                communeCodes.stream()
                        .map(code -> "codeCommuneEtablissement:" + code)
                        .collect(Collectors.joining(" OR "));
        var parameters = new LinkedHashMap<String, String>();
        parameters.put(
                "q",
                "("
                        + communes
                        + ") AND periode(etatAdministratifEtablissement:A"
                        + " AND caractereEmployeurEtablissement:O AND -dateFin:*)");
        parameters.put("tri", "siret");
        parameters.put("nombre", String.valueOf(PAGE_SIZE));
        parameters.put("curseur", cursor);
        parameters.put("masquerValeursNulles", "true");
        var search = new StringBuilder("/siret?");
        parameters.forEach(
                (name, value) ->
                        search.append(name)
                                .append('=')
                                .append(URLEncoder.encode(value, StandardCharsets.UTF_8))
                                .append('&'));
        search.setLength(search.length() - 1);
        return URI.create(properties.directoryUrl().toString() + search);
    }

    /** The wait a 429 asks for, in seconds or as a date; empty when absent or unreadable. */
    private Optional<Duration> retryAfter(HttpResponse<?> response) {
        Optional<String> header = response.headers().firstValue("Retry-After").map(String::strip);
        if (header.isEmpty()) return Optional.empty();
        try {
            return Optional.of(Duration.ofSeconds(Math.max(0, Long.parseLong(header.get()))));
        } catch (NumberFormatException notSeconds) {
            return ExternalHttpHeaders.instant(header.get())
                    .map(at -> Duration.between(clock.instant(), at))
                    .map(wait -> wait.isNegative() ? Duration.ZERO : wait);
        }
    }

    private void count(String outcome) {
        requests.get(outcome).increment();
    }

    private static LocalDate date(String text) {
        if (text == null) return null;
        try {
            return LocalDate.parse(text);
        } catch (DateTimeException error) {
            return null;
        }
    }
}
