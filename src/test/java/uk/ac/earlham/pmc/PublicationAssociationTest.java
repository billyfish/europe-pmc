package uk.ac.earlham.pmc;

import org.junit.jupiter.api.Test;

import java.util.List;

import tools.jackson.databind.JsonNode;
import uk.ac.earlham.pmc.ArticlesSearchSet.Outcome;

import static org.junit.jupiter.api.Assertions.*;

/** Attaching articles search results to the right grant, using canned responses. */
class PublicationAssociationTest {

    private static Grant grant(String id) {
        return new Grant(id, "Wellcome", "Earlham", List.of());
    }

    private static JsonNode response(String... titles) {
        StringBuilder sb = new StringBuilder("{\"hitCount\":" + titles.length + ",\"resultList\":{\"result\":[");
        for (int i = 0; i < titles.length; i++) {
            if (i > 0) sb.append(',');
            sb.append("{\"id\":\"").append(titles[i]).append("\",\"source\":\"MED\",\"title\":\"").append(titles[i]).append("\"}");
        }
        return BaseSearch.MAPPER.readTree(sb.append("]}}").toString());
    }

    private static Outcome ok(Grant g, JsonNode json) {
        return new Outcome(new ArticlesSearch(g), json, null);
    }

    private static List<String> titles(Grant g) {
        return g.getAsJSON(BaseSearch.MAPPER).get("publications").valueStream().map(p -> p.get("name").asString()).toList();
    }

    @Test
    void eachGrantGetsOnlyItsOwnPublicationsWithoutDuplicatesUpToTheLimit() {
        Grant g1 = grant("G1"), g2 = grant("G2");
        var search = new PmcSearch("Earlham", 2, 2, 1);

        var status = search.addPublications(List.of(
                ok(g1, response("A", "A", "B", "C")),   // duplicate dropped, then capped at 2
                ok(g2, response("X"))));

        assertEquals(PmcSearch.Status.STATUS_FULLY_SUCCEEDED, status);
        assertEquals(List.of("A", "B"), titles(g1));
        assertEquals(List.of("X"), titles(g2));
    }

    @Test
    void aFailedSearchIsReportedWithoutAffectingTheOtherGrants() {
        Grant good = grant("G1"), bad = grant("G2");
        var search = new PmcSearch("Earlham", 2, 3, 1);

        var status = search.addPublications(List.of(
                new Outcome(new ArticlesSearch(bad), null, "Europe PMC returned HTTP 500"),
                ok(good, response("A"))));

        assertEquals(PmcSearch.Status.STATUS_PARTIALLY_SUCCEEDED, status);
        assertEquals(List.of("A"), titles(good));
        assertEquals(0, bad.getNumberOfPublications());
        assertEquals(List.of("Europe PMC returned HTTP 500"), search.getErrors());
    }

    @Test
    void emptyOrMissingResultsGiveNoPublicationsButStillSucceed() {
        Grant empty = grant("G1"), missing = grant("G2"), oddShape = grant("G3");
        var search = new PmcSearch("Earlham", 3, 3, 1);

        var status = search.addPublications(List.of(
                ok(empty, response()),
                ok(missing, BaseSearch.MAPPER.readTree("{\"hitCount\":0}")),
                ok(oddShape, BaseSearch.MAPPER.readTree("{\"resultList\":{\"result\":\"nope\"}}"))));

        assertEquals(PmcSearch.Status.STATUS_FULLY_SUCCEEDED, status);
        assertEquals(0, empty.getNumberOfPublications() + missing.getNumberOfPublications() + oddShape.getNumberOfPublications());
        // An empty result list is normal, but a missing or malformed one is worth a warning
        var errors = search.getErrors();
        assertEquals(2, errors.size(), errors.toString());
        assertTrue(errors.get(0).startsWith("Failed to get list of Article results from:"), errors.get(0));
        assertTrue(errors.get(1).startsWith("Article results are not an array:"), errors.get(1));
    }

    @Test
    void zeroPublicationsPerGrantMeansNone() {
        Grant g = grant("G1");
        new PmcSearch("Earlham", 1, 0, 1).addPublications(List.of(ok(g, response("A"))));
        assertEquals(0, g.getNumberOfPublications());
    }

    @Test
    void publicationsMissingOptionalFieldsAreKept() {
        Grant g = grant("G1");
        JsonNode json = BaseSearch.MAPPER.readTree("{\"resultList\":{\"result\":[{\"id\":\"1\",\"title\":\"No DOI or year\"}]}}");
        new PmcSearch("Earlham", 1, 1, 1).addPublications(List.of(ok(g, json)));

        JsonNode pub = g.getAsJSON(BaseSearch.MAPPER).get("publications").get(0);
        assertEquals("No DOI or year", pub.get("name").asString());
        assertTrue(pub.get("sameAs").isNull());
    }
}
