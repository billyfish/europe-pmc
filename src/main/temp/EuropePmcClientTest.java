package uk.ac.earlham.pmc;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Runs EuropePmcClient against a local fake Europe PMC search endpoint. */
class EuropePmcClientTest {

    private HttpServer server;
    private final Deque<String> responses = new ArrayDeque<>();
    private final List<Map<String, String>> requests = new ArrayList<>();
    /** Responses keyed by the query parameter; used before the responses queue. */
    private final Map<String, String> byQuery = new HashMap<>();
    private int status = 200;
    /** If set, the server holds each response until this is released. */
    private volatile CountDownLatch gate;
    /** If set, counted down as each request arrives. */
    private volatile CountDownLatch arrived;
    private EuropePmcClient client;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/search", exchange -> {
            String response;
            synchronized (this) {
                Map<String, String> params = queryParams(exchange.getRequestURI().getRawQuery());
                requests.add(params);
                response = byQuery.containsKey(params.get("query")) ? byQuery.get(params.get("query"))
                        : responses.isEmpty() ? page(null) : responses.poll();
            }
            if (arrived != null) arrived.countDown();
            if (gate != null) {
                try {
                    gate.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] body = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/search");
        client = new EuropePmcClient(HttpClient.newHttpClient(), uri, BaseSearch.MAPPER);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    // ---- helpers to build fake responses ----

    private static String article(String id, String... grantIds) {
        StringBuilder grants = new StringBuilder();
        for (String g : grantIds) {
            if (grants.length() > 0) grants.append(',');
            grants.append(g == null ? "{\"agency\":\"X\"}" : "{\"agency\":\"X\",\"grantId\":\"" + g + "\"}");
        }
        return "{\"id\":\"" + id + "\",\"source\":\"MED\",\"pmid\":\"" + id + "\",\"doi\":\"10.1/" + id + "\","
                + "\"title\":\"Title " + id + "\",\"authorString\":\"Smith J.\",\"pubYear\":\"2025\","
                + "\"journalInfo\":{\"journal\":{\"title\":\"Journal " + id + "\"}},"
                + "\"grantsList\":{\"grant\":[" + grants + "]}}";
    }

    private static String page(String nextCursorMark, String... articles) {
        return "{\"hitCount\":" + articles.length + ","
                + (nextCursorMark == null ? "" : "\"nextCursorMark\":\"" + nextCursorMark + "\",")
                + "\"resultList\":{\"result\":[" + String.join(",", articles) + "]}}";
    }

    private static Map<String, String> queryParams(String rawQuery) {
        Map<String, String> params = new HashMap<>();
        for (String pair : rawQuery.split("&")) {
            String[] kv = pair.split("=", 2);
            params.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8), URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
        }
        return params;
    }

    /** The IDs of the publications a lookup found. */
    private List<String> ids(PublicationLookup lookup) {
        return lookup.publications().stream().map(Publication::id).toList();
    }

    // ---- tests ----

    @Test
    void sendsGrantIdQueryWithCoreJsonResults() throws Exception {
        client.findPublicationsForGrant(" BB/X011089/1 ", 5).get();
        assertEquals(1, requests.size());
        Map<String, String> p = requests.get(0);
        assertEquals("GRANT_ID:\"BB/X011089/1\"", p.get("query"));
        assertEquals("json", p.get("format"));
        assertEquals("core", p.get("resultType"));
        assertEquals("*", p.get("cursorMark"));
    }

    @Test
    void keepsOnlyArticlesWithMatchingGrantId() throws Exception {
        responses.add(page(null,
                article("1", "BB/X011089/1"),
                article("2", "BBX011089/1", null),        // near miss and a grant with no grantId
                article("3", "NE/Z1/1", "bb/x011089/1 "), // case and whitespace differ
                "{\"id\":\"4\"}"));                       // no grantsList at all
        var lookup = client.findPublicationsForGrant("BB/X011089/1", 10).get();
        assertEquals(PublicationLookup.Status.FOUND, lookup.status());
        assertEquals(List.of("1", "3"), ids(lookup));
        assertNull(lookup.error());
    }

    @Test
    void mapsPublicationFields() throws Exception {
        responses.add(page(null, article("42", "G1")));
        Publication p = client.findPublicationsForGrant("G1", 1).get().publications().get(0);
        assertEquals(new Publication("42", "MED", "42", null, "10.1/42", "Title 42", "Smith J.", "Journal 42", "2025"), p);
        assertEquals("{\"id\":\"42\",\"source\":\"MED\",\"pmid\":\"42\",\"doi\":\"10.1/42\",\"title\":\"Title 42\","
                        + "\"authorString\":\"Smith J.\",\"journal\":\"Journal 42\",\"pubYear\":\"2025\"}",
                BaseSearch.MAPPER.writeValueAsString(p));
    }

    @Test
    void stopsAtMax() throws Exception {
        responses.add(page("next", article("1", "G1"), article("2", "G1"), article("3", "G1")));
        assertEquals(List.of("1", "2"), ids(client.findPublicationsForGrant("G1", 2).get()));
        assertEquals(1, requests.size());
    }

    @Test
    void followsCursorMarkWhenFilteringLeavesTooFew() throws Exception {
        String[] firstPage = new String[EuropePmcClient.PAGE_SIZE];
        for (int i = 0; i < firstPage.length; i++) {
            firstPage[i] = article("a" + i, i == 0 ? "G1" : "OTHER");
        }
        responses.add(page("c2", firstPage));
        responses.add(page("c3", article("b0", "G1")));
        assertEquals(List.of("a0", "b0"), ids(client.findPublicationsForGrant("G1", 5).get()));
        assertEquals(2, requests.size());
        assertEquals("c2", requests.get(1).get("cursorMark"));
    }

    @Test
    void escapesQuotesInGrantId() throws Exception {
        client.findPublicationsForGrant("A\"B", 1).get();
        assertEquals("GRANT_ID:\"A\\\"B\"", requests.get(0).get("query"));
    }

    @Test
    void rejectsBlankGrantIdOrMaxBelowOne() {
        assertThrows(IllegalArgumentException.class, () -> client.findPublicationsForGrant("  ", 5));
        assertThrows(IllegalArgumentException.class, () -> client.findPublicationsForGrant(null, 5));
        assertThrows(IllegalArgumentException.class, () -> client.findPublicationsForGrant("G1", 0));
        assertEquals(0, requests.size());
    }

    // ---- not found vs failed ----

    @Test
    void reportsNotFoundWhenThereAreNoResults() throws Exception {
        responses.add(page(null));
        var lookup = client.findPublicationsForGrant("G1", 5).get();
        assertEquals(new PublicationLookup("G1", PublicationLookup.Status.NOT_FOUND, List.of(), null), lookup);
    }

    @Test
    void reportsNotFoundWhenNoResultHasTheGrantId() throws Exception {
        responses.add(page(null, article("1", "G10"), article("2", "OTHER")));
        assertEquals(PublicationLookup.Status.NOT_FOUND, client.findPublicationsForGrant("G1", 5).get().status());
    }

    @Test
    void reportsFailedOnHttpError() throws Exception {
        status = 500;
        var lookup = client.findPublicationsForGrant("G1", 5).get();
        assertEquals(PublicationLookup.Status.FAILED, lookup.status());
        assertEquals(List.of(), lookup.publications());
        assertTrue(lookup.error().contains("HTTP 500"), lookup.error());
    }

    @Test
    void reportsFailedOnInvalidJson() throws Exception {
        responses.add("not json");
        var lookup = client.findPublicationsForGrant("G1", 5).get();
        assertEquals(PublicationLookup.Status.FAILED, lookup.status());
        assertTrue(lookup.error().startsWith("Invalid JSON from Europe PMC"), lookup.error());
    }

    @Test
    void reportsFailedWhenServerIsUnreachable() throws Exception {
        server.stop(0);
        var lookup = client.findPublicationsForGrant("G1", 5).get(10, TimeUnit.SECONDS);
        assertEquals(PublicationLookup.Status.FAILED, lookup.status());
        assertNotNull(lookup.error());
        assertFalse(lookup.error().isBlank());
    }

    /** A full first page with one match (a0) for G1, pointing on to a second page. */
    private static String fullFirstPage() {
        String[] firstPage = new String[EuropePmcClient.PAGE_SIZE];
        for (int i = 0; i < firstPage.length; i++) {
            firstPage[i] = article("a" + i, i == 0 ? "G1" : "OTHER");
        }
        return page("c2", firstPage);
    }

    @Test
    void keepsEarlierPagesAndReportsPartialIfALaterPageFails() throws Exception {
        responses.add(fullFirstPage());
        responses.add("not json");
        var lookup = client.findPublicationsForGrant("G1", 5).get();
        assertEquals(PublicationLookup.Status.PARTIAL, lookup.status());
        assertEquals(List.of("a0"), ids(lookup));
        assertTrue(lookup.error().startsWith("Results may be incomplete: page 2 of the Europe PMC results failed "
                + "after 1 publication(s) were found: Invalid JSON from Europe PMC"), lookup.error());
    }

    @Test
    void reportsPartialEvenIfNothingWasFoundBeforeTheFailure() throws Exception {
        String[] firstPage = new String[EuropePmcClient.PAGE_SIZE];
        for (int i = 0; i < firstPage.length; i++) {
            firstPage[i] = article("a" + i, "OTHER");
        }
        responses.add(page("c2", firstPage));
        responses.add("not json");
        // Not NOT_FOUND: the later page might have had matches
        var lookup = client.findPublicationsForGrant("G1", 5).get();
        assertEquals(PublicationLookup.Status.PARTIAL, lookup.status());
        assertEquals(List.of(), lookup.publications());
    }

    // ---- several grants ----

    @Test
    void looksUpSeveralGrantsInOrderSkippingBlanksAndDuplicates() throws Exception {
        // The fake server answers by query, so concurrent requests can't get each other's responses
        byQuery.put("GRANT_ID:\"G1\"", page(null, article("1", "G1")));
        byQuery.put("GRANT_ID:\"G2\"", page(null));
        byQuery.put("GRANT_ID:\"G3\"", "not json");
        var lookups = client.findPublicationsForGrants(java.util.Arrays.asList("G1", " ", null, "G2", "G1 ", "G3"), 5).get();
        assertEquals(List.of("G1", "G2", "G3"), lookups.stream().map(PublicationLookup::grantId).toList());
        assertEquals(List.of(PublicationLookup.Status.FOUND, PublicationLookup.Status.NOT_FOUND,
                        PublicationLookup.Status.FAILED),
                lookups.stream().map(PublicationLookup::status).toList());
        assertEquals(3, requests.size());
    }

    @Test
    void looksUpNothingWhenMaxIsZero() throws Exception {
        assertEquals(List.of(), client.findPublicationsForGrants(List.of("G1", "G2"), 0).get());
        assertEquals(0, requests.size());
    }

    @Test
    void serialisesStatusAndOmitsMissingError() throws Exception {
        assertEquals("{\"grantId\":\"G1\",\"status\":\"not_found\",\"publications\":[]}",
                BaseSearch.MAPPER.writeValueAsString(PublicationLookup.succeeded("G1", List.of())));
        assertEquals("{\"grantId\":\"G1\",\"status\":\"failed\",\"publications\":[],\"error\":\"HTTP 500\"}",
                BaseSearch.MAPPER.writeValueAsString(PublicationLookup.failed("G1", "HTTP 500")));
        assertEquals("{\"grantId\":\"G1\",\"status\":\"partial\",\"publications\":[],\"error\":\"page 2\"}",
                BaseSearch.MAPPER.writeValueAsString(PublicationLookup.partial("G1", List.of(), "page 2")));
    }

    // ---- asynchronous behaviour ----

    @Test
    void returnsBeforeTheResponseArrives() throws Exception {
        gate = new CountDownLatch(1);
        responses.add(page(null, article("1", "G1")));
        CompletableFuture<PublicationLookup> future = client.findPublicationsForGrant("G1", 5);
        assertFalse(future.isDone());
        gate.countDown();
        assertEquals(List.of("1"), ids(future.get(10, TimeUnit.SECONDS)));
    }

    @Test
    void runsLookupsForDifferentGrantsConcurrently() throws Exception {
        // Both requests must reach the server before either is answered
        gate = new CountDownLatch(1);
        arrived = new CountDownLatch(2);
        // Responses go out in arrival order, so make each one match either grant
        responses.add(page(null, article("1", "G1", "G2")));
        responses.add(page(null, article("2", "G1", "G2")));
        var f1 = client.findPublicationsForGrant("G1", 5);
        var f2 = client.findPublicationsForGrant("G2", 5);
        assertTrue(arrived.await(10, TimeUnit.SECONDS), "requests were not sent concurrently");
        gate.countDown();
        CompletableFuture.allOf(f1, f2).get(10, TimeUnit.SECONDS);
        assertEquals(1, f1.join().publications().size());
        assertEquals(1, f2.join().publications().size());
    }
}
