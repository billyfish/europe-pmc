package uk.ac.earlham.pmc;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/** How an institution name becomes the Europe PMC query. No network needed. */
class InstitutionQueryTest {

    /** The decoded value of the query= parameter that GrantsSearch would send. */
    private static String queryFor(String institution) {
        String uri = new GrantsSearch(institution, 5, 3, true).getRestURI();
        // Must be a valid URI as-is, i.e. everything that needs encoding has been
        URI.create(uri);
        String encoded = uri.replaceAll(".*[?&/]query=([^&]*).*", "$1");
        return URLDecoder.decode(encoded, StandardCharsets.UTF_8);
    }

    @Test
    void searchesBothFormsOfAUniversityName() {
        assertEquals("aff:\"University of Sheffield\" OR aff:\"Sheffield University\"", queryFor("University of Sheffield"));
        assertEquals("aff:\"East Anglia University\" OR aff:\"University of East Anglia\"", queryFor("East Anglia University"));
    }

    @Test
    void leavesProperNamesAndNonUniversitiesAlone() {
        // "Open University" is a name in its own right, not "University of Open"
        assertEquals("aff:\"The Open University\"", queryFor("The Open University"));
        assertEquals("aff:\"Earlham Institute\"", queryFor("Earlham Institute"));
    }

    @Test
    void aNullNameHasNoAlternative() {
        assertNull(InstitutionNameNormaliser.getAlternativeName(null));
    }

    @Test
    void specialCharactersCannotBreakOutOfTheQuery() {
        // An unencoded & would start a new parameter, e.g. overriding format=json
        String uri = new GrantsSearch("Smith & Jones&format=xml", 5, 3, true).getRestURI();
        assertFalse(uri.contains("format=xml"), uri);
        assertEquals("aff:\"Smith & Jones&format=xml\"", queryFor("Smith & Jones&format=xml"));
    }
}
