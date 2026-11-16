package uk.ac.earlham.pmc;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SearchResponseTest {

    @Test
    void serialisesWithRequestParameterNamesAndEscaping() {
        var response = SearchResponse.empty(new GrantsSearch("O\"Brien\n", 5, 3));
        String json = BaseSearch.MAPPER.writeValueAsString(response);
        assertEquals("{\"institution\":\"O\\\"Brien\\n\",\"limit\":5,\"publicationsPerGrant\":3,"
                + "\"grants\":[],\"publications\":[]}", json);
    }

    @Test
    void includesEachLookupWithItsFailureDetails() {
        var pub = new Publication("1", "MED", null, null, null, "T", null, null, null);
        var response = SearchResponse.of(new GrantsSearch("Earlham", 5, 3), List.of(
                PublicationLookup.succeeded("G1", List.of(pub)),
                PublicationLookup.succeeded("G2", List.of()),
                PublicationLookup.failed("G3", "Europe PMC returned HTTP 500"),
                PublicationLookup.partial("G4", List.of(pub), "Results may be incomplete: page 2 failed")));
        String json = BaseSearch.MAPPER.writeValueAsString(response);
        assertEquals("{\"institution\":\"Earlham\",\"limit\":5,\"publicationsPerGrant\":3,\"grants\":[],\"publications\":["
                + "{\"grantId\":\"G1\",\"status\":\"found\",\"publications\":[{\"id\":\"1\",\"source\":\"MED\",\"title\":\"T\"}]},"
                + "{\"grantId\":\"G2\",\"status\":\"not_found\",\"publications\":[]},"
                + "{\"grantId\":\"G3\",\"status\":\"failed\",\"publications\":[],\"error\":\"Europe PMC returned HTTP 500\"},"
                + "{\"grantId\":\"G4\",\"status\":\"partial\",\"publications\":[{\"id\":\"1\",\"source\":\"MED\",\"title\":\"T\"}],"
                + "\"error\":\"Results may be incomplete: page 2 failed\"}]}", json);
    }

    @Test
    void roundTrips() {
        String json = "{\"institution\":\"Earlham\",\"limit\":1,\"publicationsPerGrant\":2,\"grants\":[{\"id\":\"G1\"}],"
                + "\"publications\":[{\"grantId\":\"G1\",\"status\":\"partial\",\"publications\":[],\"error\":\"x\"}]}";
        SearchResponse r = BaseSearch.MAPPER.readValue(json, SearchResponse.class);
        assertEquals("Earlham", r.institution());
        assertEquals(1, r.limit());
        assertEquals(2, r.publicationsPerGrant());
        assertEquals("G1", r.grants().get(0).get("id").asString());
        assertEquals(PublicationLookup.partial("G1", List.of(), "x"), r.publications().get(0));
    }
}
