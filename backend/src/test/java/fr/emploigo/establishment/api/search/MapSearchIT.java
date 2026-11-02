package fr.emploigo.establishment.api.search;

import static org.hamcrest.Matchers.both;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import fr.emploigo.auth.application.AuthenticationTestSupport;
import fr.emploigo.support.CsrfTokens;
import fr.emploigo.establishment.domain.SearchCriteria;
import fr.emploigo.support.IntegrationTest;
import fr.emploigo.support.TableOutage;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * The routes of the map over PostgreSQL, PostGIS and pg_trgm, with fictitious establishments: what
 * the young people see, and what they never see.
 */
class MapSearchIT extends IntegrationTest {
    private static final String BAKERY = "12345678900012";
    private static final String BRASSERIE = "22345678900012";
    private static final String AGENCY = "32345678900012";
    private static final String HIDDEN = "42345678900012";
    private static final String CLOSED = "52345678900012";

    /** Near the bakery, about 1 km west of the brasserie. */
    private static final String AROUND_THE_BRASSERIE = "5.0415,47.3220,";

    @Autowired MockMvc mvc;
    @Autowired ApplicationContext context;

    @Value("${emploi-go.establishment-images.directory}")
    Path images;

    private Cookie youth;

    @BeforeEach
    void publishFictitiousEstablishments() {
        var authentication = AuthenticationTestSupport.of(context);
        youth = authentication.cookie(authentication.newAccount("Carte"), "JEUNE");
        establishment(
                BAKERY, "LE FOURNIL DE LA GARE", "10.71C", 5.0273, 47.3233, "02", true, null);
        establishment(
                BRASSERIE, "BRASSERIE DES DUCS", "56.10A", 5.0415, 47.3220, "11", true, null);
        establishment(AGENCY, "INTERIM FICTIF", "78.20Z", null, null, "41", true, null);
        establishment(HIDDEN, "CACHE FICTIF", "10.71C", 5.0300, 47.3200, "02", false, null);
        establishment(CLOSED, "FERME FICTIF", "10.71C", 5.0300, 47.3200, "02", true, "NOT_FOUND");
        jdbc.update("INSERT INTO transit_lines (name, mode) VALUES ('T1', 'TRAM'), ('10', 'BUS')");
        stop("Gare", "TRAM", 5.0270, 47.3235, "T1");
        stop("Talant Centre", "BUS", 4.9959, 47.3389, "10");
        jdbc.update(
                """
                INSERT INTO address_streets (commune_code, name, longitude, latitude)
                VALUES ('21231', 'Rue de la Liberté', 5.0350, 47.3220)
                """);
        jdbc.update(
                """
                INSERT INTO address_numbers (street_id, label, number, longitude, latitude)
                SELECT s.id, n.label, n.number, n.longitude, n.latitude
                FROM address_streets s,
                     (VALUES ('6', 6, 5.0351, 47.3221), ('6 bis', 6, 5.0352, 47.3222),
                             ('8', 8, 5.0353, 47.3223)) AS n (label, number, longitude, latitude)
                """);
    }

    static Stream<String> routes() {
        return Stream.of(
                "/api/establishments",
                "/api/establishments/points",
                "/api/establishments/suggestions?q=fournil",
                "/api/establishments/" + BAKERY,
                "/api/places/suggestions",
                "/api/datasets",
                "/api/establishment-images/fournil.webp");
    }

    @ParameterizedTest
    @MethodSource("routes")
    void anonymousVisitorIsRefused(String route) throws Exception {
        mvc.perform(get(route).header("Place-Query", "gare"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @ParameterizedTest
    @MethodSource("routes")
    void accountOtherThanAYouthIsRefused(String route) throws Exception {
        var authentication = AuthenticationTestSupport.of(context);
        var admin = authentication.cookie(authentication.newAccount("Admin"), "ADMIN");

        mvc.perform(get(route).header("Place-Query", "gare").cookie(admin))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @ParameterizedTest
    @MethodSource("routes")
    void writeIsRefusedEvenWithItsCsrfToken(String route) throws Exception {
        mvc.perform(post(route).with(CsrfTokens.valid()).cookie(youth))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void listHasOnlyTheVisibleAndRetainedEstablishmentsWithTheirNamesShown() throws Exception {
        mvc.perform(as(get("/api/establishments").param("sort", "NAME")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(
                        jsonPath("$.items[*].name")
                                .value(
                                        contains(
                                                "Brasserie des Ducs",
                                                "Interim Fictif",
                                                "Le Fournil de la Gare")));
    }

    @ParameterizedTest
    @CsvSource({
        "q, FOURNÎL, " + BAKERY,
        "q, fournill, " + BAKERY,
        "q, restauration, " + BRASSERIE,
        "job, Boulanger / Boulangère, " + BAKERY,
        "sector, HOSPITALITY_TOURISM, " + BRASSERIE,
        "companyType, TEMP_AGENCY, " + AGENCY,
        "size, SMALL, " + BAKERY,
        "transit, true, " + BAKERY
    })
    void eachFilterKeepsTheEstablishmentsThatMeetIt(String filter, String value, String siret)
            throws Exception {
        mvc.perform(as(get("/api/establishments").param(filter, value)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].siret").value(contains(siret)));
    }

    @Test
    void searchAroundAPlaceKeepsTheEstablishmentsOfTheCircleClosestFirst() throws Exception {
        mvc.perform(
                        as(get("/api/establishments").param("sort", "DISTANCE"))
                                .header("Search-Area", AROUND_THE_BRASSERIE + "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].siret").value(contains(BRASSERIE, BAKERY)))
                .andExpect(jsonPath("$.items[0].distanceMeters").value(0))
                .andExpect(
                        jsonPath("$.items[1].distanceMeters")
                                .value(both(greaterThan(1050)).and(lessThan(1110))));
    }

    @Test
    void smallerCircleLeavesOutTheFartherEstablishment() throws Exception {
        mvc.perform(
                        as(get("/api/establishments"))
                                .header("Search-Area", AROUND_THE_BRASSERIE + "1"))
                .andExpect(jsonPath("$.items[*].siret").value(contains(BRASSERIE)));
    }

    @Test
    void groupOfPointsListsItsEstablishmentsOnly() throws Exception {
        mvc.perform(as(get("/api/establishments").param("siret", BAKERY, AGENCY, HIDDEN)))
                .andExpect(jsonPath("$.items[*].siret").value(containsInAnyOrder(BAKERY, AGENCY)));
    }

    @Test
    void pageGivesItsPartOfTheListAndTheTotal() throws Exception {
        mvc.perform(
                        as(
                                get("/api/establishments")
                                        .param("sort", "NAME")
                                        .param("page", "1")
                                        .param("pageSize", "2")))
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.items[*].siret").value(contains(BAKERY)));
    }

    @Test
    void pointsAreTheEstablishmentsWithAPosition() throws Exception {
        mvc.perform(as(get("/api/establishments/points")))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.items[*].siret").value(containsInAnyOrder(BAKERY, BRASSERIE)));
    }

    @Test
    void unreadableSearchAreaIsRefused() throws Exception {
        mvc.perform(as(get("/api/establishments/points")).header("Search-Area", "5.04;47.32;5"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    static Stream<Arguments> invalidSearches() {
        return Stream.of(
                Arguments.of("q", "a".repeat(101)),
                Arguments.of("pageSize", "51"),
                Arguments.of("page", "1001"),
                Arguments.of("sector", "NOPE"),
                Arguments.of("siret", "123"));
    }

    @ParameterizedTest
    @MethodSource("invalidSearches")
    void invalidSearchIsRefused(String parameter, String value) throws Exception {
        mvc.perform(as(get("/api/establishments").param(parameter, value)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    @Test
    void malformedSiretOfAPageIsRefused() throws Exception {
        mvc.perform(as(get("/api/establishments/{siret}", "123")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void groupOfTheLargestSizeTheMapOpensIsListed() throws Exception {
        mvc.perform(as(get("/api/establishments").param("siret", group(SearchCriteria.MAX_SIRETS))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2));
    }

    @Test
    void groupLargerThanTheMapOpensIsRefused() throws Exception {
        mvc.perform(
                        as(
                                get("/api/establishments")
                                        .param("siret", group(SearchCriteria.MAX_SIRETS + 1))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    @Test
    void listIsNeverKeptByACache() throws Exception {
        mvc.perform(as(get("/api/establishments")))
                .andExpect(header().string("Cache-Control", containsString("no-store")));
    }

    @Test
    void unavailableDatabaseMakesTheMapUnavailableWithoutCache() throws Exception {
        try (var outage = TableOutage.of(jdbc, "dataset_publications")) {
            mvc.perform(as(get("/api/datasets")))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("UNAVAILABLE"))
                    .andExpect(header().string("Cache-Control", "no-store"));
        }
    }

    @Test
    void valuesOfOneFilterAddUpAndFiltersNarrowEachOther() throws Exception {
        mvc.perform(
                        as(
                                get("/api/establishments")
                                        .param("sector", "FOOD", "HOSPITALITY_TOURISM")
                                        .param("sort", "NAME")))
                .andExpect(jsonPath("$.items[*].siret").value(contains(BRASSERIE, BAKERY)));
        mvc.perform(as(get("/api/establishments").param("sector", "FOOD").param("size", "MEDIUM")))
                .andExpect(jsonPath("$.total").value(0));
    }

    @ParameterizedTest
    @CsvSource({"250, true", "350, false"})
    void establishmentIsAccessibleByTransitWithAStopWithin300Meters(int meters, boolean accessible)
            throws Exception {
        stop("Arrêt mesuré", "BUS", 5.0415, 47.3220 + meters / 111_320.0, "10");

        mvc.perform(as(get("/api/establishments").param("transit", "true")))
                .andExpect(
                        jsonPath("$.items[*].siret")
                                .value(accessible ? hasItem(BRASSERIE) : not(hasItem(BRASSERIE))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"cache", "ferme"})
    void hiddenOrExcludedEstablishmentIsNeverSuggested(String name) throws Exception {
        mvc.perform(as(get("/api/establishments/suggestions").param("q", name)))
                .andExpect(jsonPath("$.establishments").isEmpty());
    }

    @Test
    void textWithoutLetterNorDigitOfTheSearchFormFindsNothing() throws Exception {
        mvc.perform(as(get("/api/establishments").param("q", "日本")))
                .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void photoOfAnEstablishmentIsServedFromTheFolderOfTheServer() throws Exception {
        byte[] photo = {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P'};
        Files.createDirectories(images);
        Files.write(images.resolve("fournil.webp"), photo);
        jdbc.update(
                "UPDATE establishments SET image_file = 'fournil.webp' WHERE siret = ?", BAKERY);

        mvc.perform(as(get("/api/establishments/{siret}", BAKERY)))
                .andExpect(jsonPath("$.imageUrl").value("/api/establishment-images/fournil.webp"));
        mvc.perform(as(get("/api/establishment-images/fournil.webp")))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/webp"))
                .andExpect(content().bytes(photo))
                .andExpect(header().string("Cache-Control", "max-age=86400, private"));
    }

    @Test
    void establishmentWithoutPhotoShowsTheVisualOfItsSector() throws Exception {
        mvc.perform(as(get("/api/establishments").param("siret", BAKERY)))
                .andExpect(jsonPath("$.items[0].imageUrl").value(nullValue()));
    }

    @Test
    void pageOfAnEstablishmentHasItsNearestStopAndTheJobsOfItsActivity() throws Exception {
        mvc.perform(as(get("/api/establishments/{siret}", BAKERY)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Le Fournil de la Gare"))
                .andExpect(jsonPath("$.address").value("1 Rue Fictive 21000 Dijon"))
                .andExpect(jsonPath("$.activity").value("Boulangerie et boulangerie-pâtisserie"))
                .andExpect(jsonPath("$.jobs").value(hasItem("Boulanger / Boulangère")))
                .andExpect(jsonPath("$.nearestStop.name").value("Gare"))
                .andExpect(jsonPath("$.nearestStop.mode").value("TRAM"))
                .andExpect(
                        jsonPath("$.nearestStop.distanceMeters")
                                .value(both(greaterThan(20)).and(lessThan(45))));
    }

    @Test
    void establishmentWithoutPositionHasNoNearestStop() throws Exception {
        mvc.perform(as(get("/api/establishments/{siret}", AGENCY)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nearestStop").value(nullValue()));
    }

    @ParameterizedTest
    @CsvSource({HIDDEN, CLOSED, "99999999900012"})
    void establishmentOffTheMapIsNotFound(String siret) throws Exception {
        mvc.perform(as(get("/api/establishments/{siret}", siret)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ESTABLISHMENT_NOT_FOUND"));
    }

    @Test
    void jobDoneByAnEstablishmentOfTheMapIsSuggestedUnderItsSector() throws Exception {
        mvc.perform(as(get("/api/establishments/suggestions").param("q", "boulanger")))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.jobs[?(@.sector == 'FOOD')].jobs[*]")
                                .value(hasItem("Boulanger / Boulangère")));
    }

    @Test
    void jobThatStartsWithTheTextTypedComesFirst() throws Exception {
        mvc.perform(as(get("/api/establishments/suggestions").param("q", "boulang")))
                .andExpect(jsonPath("$.jobs[0].jobs[0]").value("Boulanger / Boulangère"));
    }

    @Test
    void establishmentOfTheMapIsSuggestedByItsName() throws Exception {
        mvc.perform(as(get("/api/establishments/suggestions").param("q", "fournil")))
                .andExpect(
                        jsonPath("$.establishments[*].name")
                                .value(contains("Le Fournil de la Gare")));
    }

    @Test
    void streetTypedWithANumberGivesItsAddresses() throws Exception {
        mvc.perform(places("6 rue de la liberte"))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.places[*].label")
                                .value(contains("6 Rue de la Liberté", "6 bis Rue de la Liberté")))
                .andExpect(jsonPath("$.places[0].detail").value("Dijon"));
    }

    @Test
    void communeAndStopAreSuggestedWithoutAccentNorCase() throws Exception {
        mvc.perform(places("GARE"))
                .andExpect(jsonPath("$.places[*].kind").value(contains("STOP")))
                .andExpect(jsonPath("$.places[0].mode").value("TRAM"))
                .andExpect(jsonPath("$.places[0].detail").value("T1"));
        mvc.perform(places("chenove"))
                .andExpect(jsonPath("$.places[*].label").value(contains("Chenôve")));
    }

    @Test
    void unreadablePlaceQueryIsRefused() throws Exception {
        mvc.perform(as(get("/api/places/suggestions")).header("Place-Query", "%E9%"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void sourcesGiveTheStopsAndTheDatesOfTheirFiles() throws Exception {
        jdbc.update(
                "INSERT INTO dataset_publications (dataset, published_at, imported_at)"
                        + " VALUES ('TRANSIT_STOPS', ?, ?)",
                Timestamp.from(Instant.parse("2026-10-23T09:34:39Z")),
                Timestamp.from(Instant.parse("2026-10-25T03:00:00Z")));

        mvc.perform(as(get("/api/datasets")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stops[*].name").value(contains("Gare", "Talant Centre")))
                .andExpect(jsonPath("$.stops[*].mode").value(contains("TRAM", "BUS")))
                .andExpect(jsonPath("$.transitPublishedAt").value("2026-10-23T09:34:39Z"))
                .andExpect(jsonPath("$.addressesPublishedAt").value(nullValue()));
    }

    /** The two establishments of the list and fictitious SIRET, as many as {@code size}. */
    private static String[] group(int size) {
        return Stream.concat(
                        Stream.of(BAKERY, BRASSERIE),
                        IntStream.range(0, size - 2).mapToObj("%014d"::formatted))
                .toArray(String[]::new);
    }

    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request) {
        return request.cookie(youth);
    }

    private MockHttpServletRequestBuilder places(String text) {
        return as(get("/api/places/suggestions"))
                .header(
                        "Place-Query",
                        URLEncoder.encode(text, StandardCharsets.UTF_8).replace("+", "%20"));
    }

    private void establishment(
            String siret,
            String name,
            String activity,
            Double longitude,
            Double latitude,
            String headcountRange,
            boolean visible,
            String exclusion) {
        jdbc.update(
                """
                INSERT INTO establishments (
                    siret, name, company_name, address, commune_code, longitude, latitude,
                    activity_nomenclature, activity_code, company_type, headcount_range, ess,
                    exclusion_reason, excluded_at, seen_at, visible)
                VALUES (?, ?, 'SOCIETE FICTIVE', '1 RUE FICTIVE 21000 DIJON', '21231', ?, ?,
                        'NAF_REV2', ?, ?, ?, FALSE, ?::VARCHAR,
                        CASE WHEN ?::VARCHAR IS NULL THEN NULL ELSE now() END, now(), ?)
                """,
                siret,
                name,
                longitude,
                latitude,
                activity,
                activity.equals("78.20Z") ? "TEMP_AGENCY" : "COMPANY",
                headcountRange,
                exclusion,
                exclusion,
                visible);
    }

    private void stop(String name, String mode, double longitude, double latitude, String line) {
        jdbc.update(
                """
                WITH stop AS (
                    INSERT INTO transit_stops (name, mode, longitude, latitude) VALUES (?, ?, ?, ?)
                    RETURNING id)
                INSERT INTO transit_stop_lines (stop_id, line_name) SELECT id, ? FROM stop
                """,
                name,
                mode,
                longitude,
                latitude,
                line);
    }
}
