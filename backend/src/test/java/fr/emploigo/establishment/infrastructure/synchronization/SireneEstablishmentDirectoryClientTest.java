package fr.emploigo.establishment.infrastructure.synchronization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import fr.emploigo.establishment.application.synchronization.EstablishmentDirectoryClient.Result;
import fr.emploigo.establishment.domain.DirectoryEstablishment;
import fr.emploigo.establishment.domain.Siret;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

/**
 * How the client reads the directory and bears its limits, against a local server that answers what
 * the test chose: no call leaves the machine.
 */
class SireneEstablishmentDirectoryClientTest {
    private static final Instant NOW = Instant.parse("2026-10-25T01:30:00Z");
    private static final List<String> COMMUNES = List.of("21231", "21617");
    private static final String KEY = "fictitious-key";
    private static final String ESTABLISHMENT =
            """
            {
              "siret": "%s",
              "statutDiffusionEtablissement": "O",
              "dateCreationEtablissement": "2013-09-02",
              "trancheEffectifsEtablissement": "11",
              "activitePrincipaleNAF25Etablissement": "56.11G",
              "uniteLegale": {
                "statutDiffusionUniteLegale": "O",
                "dateCreationUniteLegale": "2011-03-01",
                "categorieEntreprise": "PME",
                "denominationUniteLegale": "SOCIETE FICTIVE",
                "categorieJuridiqueUniteLegale": "5710",
                "economieSocialeSolidaireUniteLegale": "N"
              },
              "adresseEtablissement": {
                "numeroVoieEtablissement": "1",
                "indiceRepetitionEtablissement": "B",
                "typeVoieEtablissement": "RUE",
                "libelleVoieEtablissement": "FICTIVE",
                "codePostalEtablissement": "21000",
                "libelleCommuneEtablissement": "DIJON",
                "codeCommuneEtablissement": "21231",
                "coordonneeLambertAbscisseEtablissement": "856000",
                "coordonneeLambertOrdonneeEtablissement": "6692000"
              },
              "periodesEtablissement": [
                {
                  "dateFin": "2020-01-01",
                  "etatAdministratifEtablissement": "F",
                  "enseigne1Etablissement": "ANCIENNE ENSEIGNE",
                  "caractereEmployeurEtablissement": "N"
                },
                {
                  "etatAdministratifEtablissement": "A",
                  "enseigne1Etablissement": "LE BISTROT FICTIF",
                  "enseigne2Etablissement": "LE COIN FICTIF",
                  "denominationUsuelleEtablissement": "BISTROT",
                  "activitePrincipaleEtablissement": "56.10A",
                  "nomenclatureActivitePrincipaleEtablissement": "NAFRev2",
                  "caractereEmployeurEtablissement": "O"
                }
              ]
            }
            """;

    private record Answer(int status, Map<String, String> headers, String body) {}

    private final ConcurrentLinkedQueue<Answer> answers = new ConcurrentLinkedQueue<>();
    private final List<URI> requests = new CopyOnWriteArrayList<>();
    private final List<String> userAgents = new CopyOnWriteArrayList<>();
    private final List<String> keys = new CopyOnWriteArrayList<>();
    private final List<Duration> sleeps = new CopyOnWriteArrayList<>();
    private final CountDownLatch release = new CountDownLatch(1);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private HttpServer server;
    private SireneEstablishmentDirectoryClient client;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/api-sirene/3.11/siret", this::answer);
        server.start();
        client = clientOf(server.getAddress().getPort(), Duration.ofSeconds(5), KEY);
    }

    private SireneEstablishmentDirectoryClient clientOf(
            int port, Duration timeout, String apiKey) {
        return new SireneEstablishmentDirectoryClient(
                new EstablishmentSynchronizationProperties(
                        LocalTime.of(11, 0),
                        URI.create("http://127.0.0.1:" + port + "/api-sirene/3.11"),
                        apiKey,
                        timeout,
                        timeout),
                JsonMapper.builder().build(),
                meters,
                Clock.fixed(NOW, ZoneOffset.UTC),
                sleeps::add);
    }

    @AfterEach
    void stopServer() {
        release.countDown();
        server.stop(0);
    }

    private void answer(HttpExchange exchange) throws IOException {
        requests.add(exchange.getRequestURI());
        userAgents.add(exchange.getRequestHeaders().getFirst("User-Agent"));
        keys.add(exchange.getRequestHeaders().getFirst("X-INSEE-Api-Key-Integration"));
        Answer answer = answers.poll();
        if (answer == null) {
            try {
                release.await();
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
            return;
        }
        answer.headers().forEach((name, value) -> exchange.getResponseHeaders().add(name, value));
        byte[] bytes = answer.body().getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(answer.status(), bytes.length == 0 ? -1 : bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static String page(int total, String next, String... establishments) {
        return """
               {"header": {"statut": 200, "total": %d, "debut": 0, "nombre": %d,
                           "curseur": "*", "curseurSuivant": "%s"},
                "etablissements": [%s]}
               """
                .formatted(total, establishments.length, next, String.join(",", establishments));
    }

    private static String establishment(String siret) {
        return ESTABLISHMENT.formatted(siret);
    }

    private void answerWith(int status, String body) {
        answers.add(new Answer(status, Map.of(), body));
    }

    private DirectoryEstablishment onlyEstablishment(String json) {
        answerWith(200, page(1, "next", json));
        var page = (Result.Page) client.read(COMMUNES, null);
        return page.establishments().getFirst();
    }

    /** The establishment with this text in place of another in its JSON. */
    private static String establishmentWith(String from, String to) {
        return establishment("12345678900012").replace(from, to);
    }

    @Test
    void pageGivesTheEstablishmentsWithTheDataOfTheirCompany() {
        answerWith(200, page(2500, "next", establishment("12345678900012")));

        var page = (Result.Page) client.read(COMMUNES, null);

        assertThat(page.total()).isEqualTo(2500);
        assertThat(page.nextCursor()).isEqualTo("next");
        assertThat(page.establishments())
                .singleElement()
                .satisfies(
                        e -> {
                            assertThat(e.siret()).isEqualTo(new Siret("12345678900012"));
                            assertThat(e.diffusible()).isTrue();
                            assertThat(e.namedAfterPerson()).isFalse();
                            assertThat(e.companyName()).isEqualTo("SOCIETE FICTIVE");
                            assertThat(e.signs())
                                    .containsExactly("LE BISTROT FICTIF", "LE COIN FICTIF");
                            assertThat(e.tradeName()).isEqualTo("BISTROT");
                            assertThat(e.address()).isEqualTo("1B RUE FICTIVE 21000 DIJON");
                            assertThat(e.postalCode()).isEqualTo("21000");
                            assertThat(e.communeCode()).isEqualTo("21231");
                            assertThat(e.position().longitude()).isCloseTo(5.0650801, within(1e-6));
                            assertThat(e.position().latitude()).isCloseTo(47.3099618, within(1e-6));
                            assertThat(e.activityNafRev2()).isEqualTo("56.10A");
                            assertThat(e.activityNaf2025()).isEqualTo("56.11G");
                            assertThat(e.headcountRange()).isEqualTo("11");
                            assertThat(e.companyCategory()).isEqualTo("PME");
                            assertThat(e.companyCreatedOn()).isEqualTo(LocalDate.of(2011, 3, 1));
                            assertThat(e.ess()).isFalse();
                        });
    }

    @Test
    void searchAsksForTheOpenEmployersOfTheCommunesWithTheKeyAndAUserAgent() {
        answerWith(200, page(0, "*"));

        client.read(COMMUNES, null);

        URI request = requests.getFirst();
        assertThat(request.getPath()).isEqualTo("/api-sirene/3.11/siret");
        assertThat(URLDecoder.decode(request.getRawQuery(), StandardCharsets.UTF_8))
                .contains(
                        "q=(codeCommuneEtablissement:21231 OR codeCommuneEtablissement:21617)"
                                + " AND periode(etatAdministratifEtablissement:A"
                                + " AND caractereEmployeurEtablissement:O AND -dateFin:*)")
                .contains("tri=siret")
                .contains("nombre=1000")
                .contains("curseur=*");
        assertThat(keys).containsExactly(KEY);
        assertThat(userAgents.getFirst()).startsWith("Emploi Go");
    }

    @Test
    void nextPageIsAskedForWithTheCursorOfThePreviousOneEncoded() {
        answerWith(200, page(0, "AoE-next"));

        client.read(COMMUNES, "AoJ+/x==");

        assertThat(requests.getFirst().getRawQuery()).contains("curseur=AoJ%2B%2Fx%3D%3D");
    }

    @Test
    void searchThatFindsNothingIsAnEmptyPage() {
        answerWith(404, "{\"header\": {\"statut\": 404}}");

        Result result = client.read(COMMUNES, null);

        assertThat(result).isEqualTo(new Result.Page(0, null, List.of()));
    }

    @ParameterizedTest
    @CsvSource(
            nullValues = "null",
            value = {"null, *", "same, same"})
    void lastPageIsTheOneThatReturnsTheCursorItWasAskedWith(String asked, String returned) {
        answerWith(200, page(1, returned, establishment("12345678900012")));

        var page = (Result.Page) client.read(COMMUNES, asked);

        assertThat(page.nextCursor()).isNull();
    }

    @Test
    void emptyPageIsTheLastEvenWithAnotherCursor() {
        answerWith(200, page(0, "other"));

        var page = (Result.Page) client.read(COMMUNES, "same");

        assertThat(page.nextCursor()).isNull();
    }

    @Test
    void establishmentOpposedToDiffusionIsReadAsNotDiffusible() {
        var establishment =
                onlyEstablishment(
                        establishmentWith(
                                "\"statutDiffusionEtablissement\": \"O\"",
                                "\"statutDiffusionEtablissement\": \"P\""));

        assertThat(establishment.diffusible()).isFalse();
    }

    @Test
    void companyOpposedToDiffusionHasNoDiffusibleEstablishment() {
        var establishment =
                onlyEstablishment(
                        establishmentWith(
                                "\"statutDiffusionUniteLegale\": \"O\"",
                                "\"statutDiffusionUniteLegale\": \"P\""));

        assertThat(establishment.diffusible()).isFalse();
    }

    @ParameterizedTest
    @CsvSource({"1000, true", "2110, true", "2210, true", "2310, true", "5499, false"})
    void companyOfAPersonIsNamedAfterAPerson(String legalCategory, boolean namedAfterPerson) {
        var establishment =
                onlyEstablishment(
                        establishmentWith(
                                "\"categorieJuridiqueUniteLegale\": \"5710\"",
                                "\"categorieJuridiqueUniteLegale\": \"" + legalCategory + "\""));

        assertThat(establishment.namedAfterPerson()).isEqualTo(namedAfterPerson);
    }

    @Test
    void companyWithoutLegalCategoryIsNamedAfterAPersonInDoubt() {
        var establishment =
                onlyEstablishment(
                        establishmentWith(
                                "\"categorieJuridiqueUniteLegale\": \"5710\",", ""));

        assertThat(establishment.namedAfterPerson()).isTrue();
    }

    @Test
    void companyOfTheSocialAndSolidaryEconomyIsRead() {
        var establishment =
                onlyEstablishment(
                        establishmentWith(
                                "\"economieSocialeSolidaireUniteLegale\": \"N\"",
                                "\"economieSocialeSolidaireUniteLegale\": \"O\""));

        assertThat(establishment.ess()).isTrue();
    }

    @Test
    void activityInNaf2025OfThePeriodIsReadOnceTheInseeStopsGivingItApart() {
        var establishment =
                onlyEstablishment(
                        establishment("12345678900012")
                                .replace("\"activitePrincipaleNAF25Etablissement\": \"56.11G\",", "")
                                .replace("\"56.10A\"", "\"56.11G\"")
                                .replace("\"NAFRev2\"", "\"NAF2025\""));

        assertThat(establishment.activityNaf2025()).isEqualTo("56.11G");
        assertThat(establishment.activityNafRev2()).isNull();
    }

    @Test
    void establishmentWithoutCoordinatesHasNoPosition() {
        var establishment =
                onlyEstablishment(
                        establishment("12345678900012")
                                .replace("\"856000\"", "null")
                                .replace("\"6692000\"", "null"));

        assertThat(establishment.position()).isNull();
    }

    @Test
    void establishmentWithoutStreetHasNoAddress() {
        var establishment =
                onlyEstablishment(
                        establishmentWith(
                                "\"libelleVoieEtablissement\": \"FICTIVE\",", ""));

        assertThat(establishment.address()).isNull();
    }

    @ParameterizedTest
    @CsvSource({
        "'\"siret\": \"12345678900012\"', '\"siret\": \"1234\"'",
        "'\"statutDiffusionEtablissement\": \"O\",', ''",
        "'\"statutDiffusionUniteLegale\": \"O\",', ''",
        "'\"etatAdministratifEtablissement\": \"A\",', '\"dateFin\": \"2024-01-01\", \"etatAdministratifEtablissement\": \"A\",'"
    })
    void establishmentThatCannotBeReadIsLeftOutOfThePage(String from, String to) {
        answerWith(
                200,
                page(
                        2,
                        "next",
                        establishmentWith(from, to),
                        establishment("22345678900012")));

        var page = (Result.Page) client.read(COMMUNES, null);

        assertThat(page.total()).isEqualTo(2);
        assertThat(page.establishments())
                .extracting(e -> e.siret().value())
                .containsExactly("22345678900012");
    }

    @Test
    void keyAbsentMakesTheDirectoryUnavailableWithoutAnyRequest() {
        client = clientOf(server.getAddress().getPort(), Duration.ofSeconds(5), null);

        Result result = client.read(COMMUNES, null);

        assertThat(result).isInstanceOf(Result.Unavailable.class);
        assertThat(requests).isEmpty();
    }

    @Test
    void consecutiveRequestsAreSpacedOutUnderTheRateLimit() {
        answerWith(200, page(0, "*"));
        answerWith(200, page(0, "*"));

        client.read(COMMUNES, null);
        client.read(COMMUNES, "next");

        assertThat(sleeps).containsExactly(Duration.ofMillis(2100));
    }

    @Test
    void rateLimitedRequestIsRetriedAfterTheWaitTheDirectoryAsks() {
        answers.add(new Answer(429, Map.of("Retry-After", "7"), ""));
        answerWith(200, page(0, "*"));

        Result result = client.read(COMMUNES, null);

        assertThat(result).isInstanceOf(Result.Page.class);
        assertThat(sleeps).startsWith(Duration.ofSeconds(7));
        assertThat(requestsCounted("rate-limited")).isEqualTo(1);
    }

    @Test
    void rateLimitedRequestAskingForADateIsRetriedAtThatDate() {
        answers.add(new Answer(429, Map.of("Retry-After", "Sun, 25 Oct 2026 01:30:07 GMT"), ""));
        answerWith(200, page(0, "*"));

        Result result = client.read(COMMUNES, null);

        assertThat(result).isInstanceOf(Result.Page.class);
        assertThat(sleeps).startsWith(Duration.ofSeconds(7));
    }

    @Test
    void rateLimitedRequestWithoutWaitIsRetriedAfterTheFirstDelay() {
        answers.add(new Answer(429, Map.of(), ""));
        answerWith(200, page(0, "*"));

        Result result = client.read(COMMUNES, null);

        assertThat(result).isInstanceOf(Result.Page.class);
        assertThat(sleeps).startsWith(Duration.ofSeconds(1));
    }

    @Test
    void rateLimitedRequestAskingForMoreThanAMinuteIsNotRetried() {
        answers.add(new Answer(429, Map.of("Retry-After", "120"), ""));

        Result result = client.read(COMMUNES, null);

        assertThat(result).isInstanceOf(Result.Unavailable.class);
        assertThat(requests).hasSize(1);
    }

    @Test
    void serverErrorIsRetriedWithGrowingDelaysThenGivesUp() {
        answerWith(503, "");
        answerWith(502, "");
        answerWith(500, "");
        answerWith(503, "");

        Result result = client.read(COMMUNES, null);

        assertThat(result).isEqualTo(new Result.Unavailable("statut 503"));
        assertThat(sleeps)
                .containsSubsequence(
                        Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(4));
        assertThat(requests).hasSize(SireneEstablishmentDirectoryClient.ATTEMPTS);
    }

    @ParameterizedTest
    @CsvSource({"400, statut 400", "401, statut 401", "403, statut 403", "302, statut 302"})
    void refusedOrRedirectedRequestIsNotRetried(int status, String cause) {
        answers.add(new Answer(status, Map.of("Location", "https://example.invalid/"), ""));

        Result result = client.read(COMMUNES, null);

        assertThat(result).isEqualTo(new Result.Unavailable(cause));
        assertThat(requests).hasSize(1);
        assertThat(requestsCounted("refused")).isEqualTo(1);
    }

    @Test
    void unreadableAnswerIsRetried() {
        answerWith(200, "<html>maintenance</html>");
        answerWith(200, page(0, "*"));

        Result result = client.read(COMMUNES, null);

        assertThat(result).isInstanceOf(Result.Page.class);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "<html>maintenance</html>",
                "{\"etablissements\": []}",
                "{\"header\": {}, \"etablissements\": []}",
                "{}"
            })
    void answerThatIsNotAPageMakesTheDirectoryUnavailable(String body) {
        answerWith(200, body);
        answerWith(200, body);
        answerWith(200, body);
        answerWith(200, body);

        Result result = client.read(COMMUNES, null);

        assertThat(result).isEqualTo(new Result.Unavailable("réponse illisible"));
    }

    @Test
    void unreachableDirectoryIsUnavailable() {
        Result result = clientOf(1, Duration.ofSeconds(5), KEY).read(COMMUNES, null);

        assertThat(result).isEqualTo(new Result.Unavailable("pas de réponse"));
    }

    @Test
    void directoryThatDoesNotAnswerInTimeIsUnavailable() {
        client = clientOf(server.getAddress().getPort(), Duration.ofMillis(100), KEY);

        Result result = client.read(COMMUNES, null);

        assertThat(result).isEqualTo(new Result.Unavailable("pas de réponse"));
        assertThat(requestsCounted("failed"))
                .isEqualTo(SireneEstablishmentDirectoryClient.ATTEMPTS);
    }

    private double requestsCounted(String outcome) {
        return meters.get("establishment.directory.requests")
                .tag("outcome", outcome)
                .counter()
                .count();
    }
}
