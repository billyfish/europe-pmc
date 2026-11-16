package uk.ac.earlham.pmc;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SearchRequestTest {

    /** Builds a request parameter map from name/value pairs, as HttpServletRequest.getParameterMap() would. */
    private static Map<String, String[]> params(String... nameValuePairs) {
        Map<String, String[]> map = new HashMap<>();
        for (int i = 0; i < nameValuePairs.length; i += 2) {
            map.put(nameValuePairs[i], new String[] { nameValuePairs[i + 1] });
        }
        return map;
    }

    private static List<String> errorsFor(Map<String, String[]> params) {
        var e = assertThrows(GrantsSearch.InvalidRequestException.class, () -> PmcSearch.parse(params));
        return e.errors();
    }

    /** PmcSearch is a class rather than a record, so compare its fields. */
    private static void assertSearch(String institution, int grants, int pubs, PmcSearch actual) {
        assertEquals(institution, actual.getInstitution());
        assertEquals(grants, actual.getNumGrants());
        assertEquals(pubs, actual.getNumPublicationsPerGrant());
    }

    // ---- valid requests ----

    @Test
    void parsesAllParameters() throws Exception {
        var r = PmcSearch.parse(params("institution", "Earlham", "limit", "7", "publicationsPerGrant", "4"));
        assertSearch("Earlham", 7, 4, r);
    }

    @Test
    void stripsWhitespace() throws Exception {
        var r = PmcSearch.parse(params("institution", "  Earlham Institute ", "limit", " 2 ", "publicationsPerGrant", "1 "));
        assertSearch("Earlham Institute", 2, 1, r);
    }

    @Test
    void usesDefaultsWhenCountsAreAbsent() throws Exception {
        var r = PmcSearch.parse(params("institution", "Earlham"));
        assertEquals(PmcSearch.GRANTS_DEFAULT, r.getNumGrants());
        assertEquals(PmcSearch.PUBS_DEFAULT, r.getNumPublicationsPerGrant());
    }

    @Test
    void acceptsBoundaryValues() throws Exception {
        assertSearch("x", PmcSearch.GRANTS_MIN, PmcSearch.PUBS_MIN,
                PmcSearch.parse(params("institution", "x",
                        "limit", String.valueOf(PmcSearch.GRANTS_MIN),
                        "publicationsPerGrant", String.valueOf(PmcSearch.PUBS_MIN))));
        assertSearch("x", PmcSearch.GRANTS_MAX, PmcSearch.PUBS_MAX,
                PmcSearch.parse(params("institution", "x",
                        "limit", String.valueOf(PmcSearch.GRANTS_MAX),
                        "publicationsPerGrant", String.valueOf(PmcSearch.PUBS_MAX))));
    }

    @Test
    void ignoresUnknownParameters() throws Exception {
        var r = PmcSearch.parse(params("institution", "Earlham", "foo", "bar"));
        assertEquals("Earlham", r.getInstitution());
    }

    // ---- institution ----

    @Test
    void rejectsMissingInstitution() {
        assertEquals(List.of("'institution' is required"), errorsFor(params()));
    }

    @Test
    void doesNotValidateCountsWhenInstitutionIsMissing() {
        assertEquals(List.of("'institution' is required"), errorsFor(params("limit", "abc")));
    }

    @Test
    void rejectsBlankInstitution() {
        assertEquals(List.of("'institution' has no value"), errorsFor(params("institution", "   ")));
    }

    @Test
    void rejectsRepeatedInstitution() {
        var p = new HashMap<String, String[]>();
        p.put("institution", new String[] { "A", "B" });
        assertEquals(List.of("'institution' should only have 1 value, 2 have been set"), errorsFor(p));
    }

    // ---- limit / publicationsPerGrant ----

    @Test
    void rejectsLimitAboveMax() {
        assertEquals(List.of("'limit' must be between " + PmcSearch.GRANTS_MIN + " and " + PmcSearch.GRANTS_MAX),
                errorsFor(params("institution", "x", "limit", String.valueOf(PmcSearch.GRANTS_MAX + 1))));
    }

    @Test
    void rejectsPublicationsPerGrantAboveMax() {
        assertEquals(List.of("'publicationsPerGrant' must be between " + PmcSearch.PUBS_MIN + " and " + PmcSearch.PUBS_MAX),
                errorsFor(params("institution", "x", "publicationsPerGrant", String.valueOf(PmcSearch.PUBS_MAX + 1))));
    }

    @Test
    void rejectsNegativeCounts() {
        assertEquals(List.of("'limit' must be between " + PmcSearch.GRANTS_MIN + " and " + PmcSearch.GRANTS_MAX),
                errorsFor(params("institution", "x", "limit", "-1")));
    }

    @Test
    void rejectsPageBelowOneWithoutQuotingAnUpperLimit() {
        assertEquals(List.of("'page' must be 1 or more"), errorsFor(params("institution", "x", "page", "0")));
    }

    @Test
    void rejectsNonIntegerCounts() {
        assertEquals(List.of("'limit' must be an integer", "'publicationsPerGrant' must be an integer"),
                errorsFor(params("institution", "x", "limit", "abc", "publicationsPerGrant", "2.5")));
    }

    @Test
    void usesDefaultsWhenCountsAreBlank() throws Exception {
        // HTML forms send "limit=" when the field is left empty
        var r = PmcSearch.parse(params("institution", "x", "limit", "", "publicationsPerGrant", "  "));
        assertSearch("x", PmcSearch.GRANTS_DEFAULT, PmcSearch.PUBS_DEFAULT, r);
    }
}
