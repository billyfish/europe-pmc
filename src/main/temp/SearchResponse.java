package uk.ac.earlham.pmc;

import tools.jackson.databind.JsonNode;

import java.util.List;

/**
 * JSON body returned by GET /search. The first three fields echo the
 * request parameters, with the same names.
 *
 * @param publications one entry per grant, each with its status ("found",
 *                     "not_found", "failed" or "partial") and, if the lookup
 *                     went wrong, an "error" with the details
 */
public record SearchResponse(
        String institution,
        int limit,
        int publicationsPerGrant,
        List<JsonNode> grants,
        List<PublicationLookup> publications) {

    public static SearchResponse empty(GrantsSearch request) {
        return of(request, List.of());
    }

    public static SearchResponse of(GrantsSearch request, List<PublicationLookup> publications) {
        return new SearchResponse(request.getInstitution(), request.getNumGrants(), request.getNumPublicationsPerGrant(), List.of(), publications);
    }
}
