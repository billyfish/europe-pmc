package uk.ac.earlham.pmc;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * The outcome of looking up the publications for one grant. The status separates
 * "Europe PMC has no publications for this grant" from "we couldn't find out".
 *
 * @param grantId      the grant that was looked up
 * @param status       what happened
 * @param publications the matches; for PARTIAL, only those found before the failure
 * @param error        what went wrong; only set when status is FAILED or PARTIAL
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PublicationLookup(String grantId, Status status, List<Publication> publications, String error) {

	
	
	
    public enum Status {
        /** At least one publication lists this grant. */
        @JsonProperty("found") FOUND,
        /** Europe PMC answered, but no publication lists this grant. */
        @JsonProperty("not_found") NOT_FOUND,
        /** Europe PMC couldn't be reached or gave an unusable answer, so the result is unknown. */
        @JsonProperty("failed") FAILED,
        /** The first page(s) were read, but a later page failed, so the publications may be incomplete. */
        @JsonProperty("partial") PARTIAL
    }

    public PublicationLookup {
        publications = List.copyOf(publications);
    }

    /** A successful lookup: FOUND if there are any publications, otherwise NOT_FOUND. */
    public static PublicationLookup succeeded(String grantId, List<Publication> publications) {
        return new PublicationLookup(grantId, publications.isEmpty() ? Status.NOT_FOUND : Status.FOUND,
                publications, null);
    }

    public static PublicationLookup failed(String grantId, String error) {
        return new PublicationLookup(grantId, Status.FAILED, List.of(), error);
    }

    /** Some pages were read before one failed; publications holds what was found up to then. */
    public static PublicationLookup partial(String grantId, List<Publication> publications, String error) {
        return new PublicationLookup(grantId, Status.PARTIAL, publications, error);
    }
}
