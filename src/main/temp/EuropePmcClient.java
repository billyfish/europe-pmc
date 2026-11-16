package uk.ac.earlham.pmc;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

/**
 * Client for the Europe PMC Articles REST API search endpoint.
 *
 * @see <a href="https://europepmc.org/RestfulWebService">Europe PMC RESTful Web Service</a>
 */
public class EuropePmcClient {

    private static final System.Logger LOG = System.getLogger (EuropePmcClient.class.getName ());

    public static final URI DEFAULT_SEARCH_URI =
            URI.create ("https://www.ebi.ac.uk/europepmc/webservices/rest/search");

    /** Results requested per page. */
    static final int PAGE_SIZE = 25;

    /** Upper bound on pages fetched for one grant, so a bad query can't loop forever. */
    static final int MAX_PAGES = 10;

    private final HttpClient http;
    private final URI searchUri;
    private final ObjectMapper mapper;

    public EuropePmcClient (HttpClient http, URI searchUri, ObjectMapper mapper) {
        this.http = http;
        this.searchUri = searchUri;
        this.mapper = mapper;
    }

    /** A client for the live Europe PMC service. */
    public static EuropePmcClient create(ObjectMapper mapper) {
        HttpClient http = HttpClient.newBuilder()
                // EBI's servers often reset HTTP/2 connections (GOAWAY / STREAM_CLOSED)
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        return new EuropePmcClient(http, DEFAULT_SEARCH_URI, mapper);
    }

    /**
     * Finds publications funded by the given award, without blocking the calling thread.
     *
     * Searches with GRANT_ID:"grantId" and resultType=core (lite results have no
     * grantsList), then keeps only articles where grantsList.grant[].grantId equals
     * grantId (ignoring case and surrounding whitespace). Pages for one grant are
     * fetched in sequence, since each needs the previous page's cursorMark, but
     * lookups for different grants can run concurrently.
     *
     * <p>The returned future always completes normally. Check the result's status:
     * FOUND or NOT_FOUND if Europe PMC answered; FAILED (with an error message) if
     * the first page couldn't be fetched (unreachable, HTTP error or invalid JSON);
     * or PARTIAL if a later page failed, in which case the publications found on
     * the earlier pages are kept and the error says which page failed.
     *
     * @param grantId the award's grant ID, e.g. "BB/X011089/1"
     * @param max     the maximum number of publications to return, at least 1
     * @throws IllegalArgumentException if grantId is blank or max is less than 1
     */
    public CompletableFuture<PublicationLookup> findPublicationsForGrant(String grantId, int max) {
        String wanted = grantId == null ? "" : grantId.strip();
        if (wanted.isEmpty()) {
            throw new IllegalArgumentException("grantId must not be blank");
        }
        if (max < 1) {
            throw new IllegalArgumentException("max must be at least 1, was " + max);
        }
        String query = "GRANT_ID:\"" + wanted.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        return fetchPage(query, wanted, max, "*", 0, new ArrayList<>());
    }

    /**
     * Looks up publications for several grants concurrently.
     *
     * @param grantIds the grants to look up; blank and duplicate IDs are skipped
     * @param max      the maximum number of publications per grant; if 0, nothing is looked up
     * @return a future holding one PublicationLookup per grant, in the order given;
     *         it always completes normally
     */
    public CompletableFuture<List<PublicationLookup>> findPublicationsForGrants(List<String> grantIds, int max) {
        if (max < 1) {
            return CompletableFuture.completedFuture(List.of());
        }
        List<CompletableFuture<PublicationLookup>> lookups = grantIds.stream()
                .filter(id -> id != null && !id.isBlank())
                .map(String::strip)
                .distinct()
                .map(id -> findPublicationsForGrant(id, max))
                .toList();
        return CompletableFuture.allOf(lookups.toArray(CompletableFuture[]::new))
                .thenApply(ignored -> lookups.stream().map(CompletableFuture::join).toList());
    }

    private static Throwable unwrap(Throwable t) {
        while ((t instanceof CompletionException || t instanceof ExecutionException) && t.getCause() != null) {
            t = t.getCause();
        }
        return t;
    }

    /** A message for the response. Some exceptions (e.g. ConnectException) have none, so name the type instead. */
    private static String describe(Throwable t) {
        String message = t.getMessage();
        if (message == null || message.isBlank()) {
            return "Could not get a response from Europe PMC (" + t.getClass().getSimpleName() + ")";
        }
        return message;
    }

    /**
     * Fetches one page, adds its matches to found, and chains on to the next page if
     * needed. A failure on the first page gives FAILED; on a later page, PARTIAL.
     */
    private CompletableFuture<PublicationLookup> fetchPage(
            String query, String wanted, int max, String cursorMark, int page, List<Publication> found) {
        Map<String, String> params = Map.of(
                "query", query,
                "format", "json",
                "resultType", "core",
                "pageSize", String.valueOf(PAGE_SIZE),
                "cursorMark", cursorMark);

        CompletableFuture<JsonNode> response;
        try {
            response = fetch(params);
        } catch (RuntimeException e) {
            response = CompletableFuture.failedFuture(e);
        }

        return response.handle((body, failure) -> {
            if (failure != null) {
                return CompletableFuture.completedFuture(failedLookup(wanted, page, found, failure));
            }

            JsonNode results = body.path("resultList").path("result");
            for (JsonNode result : results) {
                if (hasGrantId(result, wanted)) {
                    found.add(Publication.fromEuropePmc(result));
                    if (found.size() == max) {
                        return CompletableFuture.completedFuture(PublicationLookup.succeeded(wanted, found));
                    }
                }
            }

            JsonNode nextNode = body.path("nextCursorMark");
            String next = nextNode.isString() ? nextNode.asString() : null;
            boolean lastPage = results.size() < PAGE_SIZE || next == null || next.equals(cursorMark);
            if (lastPage || page + 1 >= MAX_PAGES) {
                return CompletableFuture.completedFuture(PublicationLookup.succeeded(wanted, found));
            }
            return fetchPage(query, wanted, max, next, page + 1, found);
        }).thenCompose(next -> next);
    }

    /** Logs a failed page and turns it into FAILED (first page) or PARTIAL (later page) with the details. */
    private static PublicationLookup failedLookup(String grantId, int page, List<Publication> found, Throwable failure) {
        Throwable cause = unwrap(failure);
        int pageNumber = page + 1;
        LOG.log(System.Logger.Level.WARNING,
                "Europe PMC lookup for grant " + grantId + " failed on page " + pageNumber, cause);
        if (page == 0) {
            return PublicationLookup.failed(grantId, describe(cause));
        }
        return PublicationLookup.partial(grantId, found,
                "Results may be incomplete: page " + pageNumber + " of the Europe PMC results failed after "
                        + found.size() + " publication(s) were found: " + describe(cause));
    }

    /** True if any grantsList.grant entry of the article has the given grantId. */
    static boolean hasGrantId(JsonNode article, String grantId) {
        JsonNode grants = article.path("grantsList").path("grant");
        // Normally an array, but tolerate a single object
        Iterable<JsonNode> entries = grants.isObject() ? List.of(grants) : grants;
        for (JsonNode grant : entries) {
            JsonNode id = grant.path("grantId");
            if (id.isString() && id.asString().strip().equalsIgnoreCase(grantId)) {
                return true;
            }
        }
        return false;
    }

    private CompletableFuture<JsonNode> fetch(Map<String, String> params) {
        String queryString = params.entrySet().stream()
                .map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
                .collect(Collectors.joining("&"));
        HttpRequest request = HttpRequest.newBuilder(URI.create(searchUri + "?" + queryString))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json")
                .GET()
                .build();

        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenApply(response -> {
            if (response.statusCode() != 200) {
                throw new CompletionException(new IOException(
                        "Europe PMC returned HTTP " + response.statusCode() + " for " + request.uri()));
            }
            try {
                return mapper.readTree(response.body());
            } catch (JacksonException e) {
                throw new CompletionException(new IOException("Invalid JSON from Europe PMC for " + request.uri(), e));
            }
        });
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
