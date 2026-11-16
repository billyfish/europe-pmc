package uk.ac.earlham.pmc;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Runs ArticlesSearchSet against a local fake server. */
class ArticlesSearchSetTest {

    private HttpServer server;
    private String base;
    /** What the fake server sends for each grant ID: a status code and body, or "SLOW" to never answer in time. */
    private final Map<String, String[]> answers = new ConcurrentHashMap<>();
    private final Map<String, String> receivedQueries = new ConcurrentHashMap<>();
    private volatile CountDownLatch arrived;
    private final CountDownLatch release = new CountDownLatch(1);

    /** An ArticlesSearch whose buildURI points at the fake server instead of Europe PMC. */
    private class LocalSearch extends ArticlesSearch {
        LocalSearch(String grant) {
            super(new Grant(grant, null, null, null));
        }

        @Override
        public String getRestURI(int maxNumHits) {
            return base + "?grant=" + getGrantId() + "&pageSize=" + maxNumHits;
        }
    }

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/search", exchange -> {
            String query = exchange.getRequestURI().getQuery();
            String grant = query.replaceAll(".*grant=([^&]*).*", "$1");
            receivedQueries.put(grant, query);
            if (arrived != null) arrived.countDown();
            String[] answer = answers.getOrDefault(grant, new String[] { "200", "{\"hitCount\":0}" });
            if (answer[0].equals("SLOW")) {
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                answer = new String[] { "200", "{}" };
            }
            byte[] body = answer[1].getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(Integer.parseInt(answer[0]), body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort() + "/search";
    }

    @AfterEach
    void stop() {
        release.countDown();
        server.stop(0);
    }

    private ArticlesSearchSet setOf(String... grants) {
        var set = new ArticlesSearchSet(HttpClient.newHttpClient(), BaseSearch.MAPPER);
        for (String g : grants) {
            set.addArticleSearch(new LocalSearch(g));
        }
        return set;
    }

    // ---- ArticlesSearch.getRestURI ----

    @Test
    void buildUriAsksEuropePmcForCoreJsonResults() {
        assertEquals("https://www.ebi.ac.uk/europepmc/webservices/rest/search?format=json&resultType=core"
                + "&query=grant_id%3A%22BB%2FX011089%2F1%22&pageSize=25",
                new ArticlesSearch(new Grant("BB/X011089/1", null, null, null)).getRestURI(25));
    }

    // ---- ArticlesSearch.collateResults (from BaseSearch) ----

    @Test
    void collateResultsAddsNewPublicationsToTheGrantUntilItHasEnough() {
        answers.put("G1", new String[] { "200", "{\"resultList\":{\"result\":["
                + "{\"id\":\"1\",\"source\":\"MED\",\"title\":\"One\",\"journalInfo\":{\"journal\":{\"title\":\"J\"}}},"
                + "{\"id\":\"1\",\"source\":\"MED\",\"title\":\"One\",\"journalInfo\":{\"journal\":{\"title\":\"J\"}}},"
                + "{\"id\":\"2\",\"source\":\"MED\",\"title\":\"Two\"},"
                + "{\"id\":\"3\",\"source\":\"MED\",\"title\":\"Three\"}]}}" });
        var search = new LocalSearch("G1");
        search.setMaxNumHits(2);

        assertTrue(search.collateResults());
        // The duplicate of "1" is skipped and it stops once it has 2
        assertEquals(2, search.getGrant().getNumberOfPublications());
        assertEquals("grant=G1&pageSize=2", receivedQueries.get("G1"));
    }

    @Test
    void collateResultsReportsWhenThereAreTooFewPublications() {
        answers.put("G1", new String[] { "200", "{\"resultList\":{\"result\":[{\"id\":\"1\",\"source\":\"MED\"}]}}" });
        var search = new LocalSearch("G1");
        search.setMaxNumHits(3);

        assertFalse(search.collateResults());
        assertEquals(1, search.getGrant().getNumberOfPublications());
    }

    // ---- ArticlesSearch.searchAsync ----

    @Test
    void searchAsyncSendsTheBuiltUriAndParsesJson() throws Exception {
        answers.put("G1", new String[] { "200", "{\"hitCount\":3}" });
        var json = new LocalSearch("G1").searchAsync(HttpClient.newHttpClient(), BaseSearch.MAPPER, 7,
                Duration.ofSeconds(5)).get(10, TimeUnit.SECONDS);
        assertEquals(3, json.get("hitCount").asInt());
        assertEquals("grant=G1&pageSize=7", receivedQueries.get("G1"));
    }

    @Test
    void searchAsyncReturnsBeforeTheResponseArrives() throws Exception {
        answers.put("G1", new String[] { "SLOW", null });
        var future = new LocalSearch("G1").searchAsync(HttpClient.newHttpClient(), BaseSearch.MAPPER, 1,
                Duration.ofSeconds(5));
        assertFalse(future.isDone());
        release.countDown();
        assertNotNull(future.get(10, TimeUnit.SECONDS));
    }

    // ---- ArticlesSearchSet.searchAll ----

    @Test
    void searchAllReturnsOneOutcomePerSearchInOrder() {
        answers.put("G1", new String[] { "200", "{\"hitCount\":1}" });
        answers.put("G2", new String[] { "200", "{\"hitCount\":2}" });
        var outcomes = setOf("G1", "G2").searchAll(5, Duration.ofSeconds(5));
        assertEquals(List.of("G1", "G2"), outcomes.stream().map(o -> o.search().getGrantId()).toList());
        assertTrue(outcomes.stream().allMatch(ArticlesSearchSet.Outcome::succeeded));
        assertEquals(2, outcomes.get(1).response().get("hitCount").asInt());
    }

    @Test
    void searchAllSendsTheRequestsConcurrently() {
        // Neither answers until released, and the release only happens once both have arrived
        answers.put("G1", new String[] { "SLOW", null });
        answers.put("G2", new String[] { "SLOW", null });
        arrived = new CountDownLatch(2);
        var waiter = new Thread(() -> {
            try {
                if (arrived.await(5, TimeUnit.SECONDS)) release.countDown();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        waiter.start();
        var outcomes = setOf("G1", "G2").searchAll(5, Duration.ofSeconds(8));
        assertTrue(outcomes.stream().allMatch(ArticlesSearchSet.Outcome::succeeded), outcomes.toString());
    }

    @Test
    void aFailureOrTimeoutOnlyAffectsThatSearch() {
        answers.put("OK", new String[] { "200", "{\"hitCount\":1}" });
        answers.put("ERR", new String[] { "500", "oops" });
        answers.put("BAD", new String[] { "200", "not json" });
        answers.put("SLOW", new String[] { "SLOW", null });

        long start = System.nanoTime();
        var outcomes = setOf("OK", "ERR", "BAD", "SLOW").searchAll(5, Duration.ofMillis(500));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertTrue(outcomes.get(0).succeeded());
        assertNull(outcomes.get(0).error());
        assertTrue(outcomes.get(1).error().contains("HTTP 500"), outcomes.get(1).error());
        assertTrue(outcomes.get(2).error().startsWith("Invalid JSON"), outcomes.get(2).error());
        assertEquals("No response from Europe PMC within 500 ms", outcomes.get(3).error());
        assertNull(outcomes.get(3).response());
        // Waited for the timeout, not for the slow server
        assertTrue(elapsedMs < 5_000, "took " + elapsedMs + " ms");
    }

    @Test
    void anInvalidUriIsReportedAsThatSearchsFailure() {
        var outcomes = setOf("has space", "OK").searchAll(5, Duration.ofSeconds(5));
        assertFalse(outcomes.get(0).succeeded());
        assertNotNull(outcomes.get(0).error());
        assertTrue(outcomes.get(1).succeeded());
    }

    @Test
    void searchAllWithNoSearchesReturnsNothing() {
        assertEquals(List.of(), setOf().searchAll(5, Duration.ofSeconds(1)));
    }
}
