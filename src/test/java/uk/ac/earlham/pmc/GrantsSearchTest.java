package uk.ac.earlham.pmc;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Collecting grants from a fake Grist API instead of Europe PMC. */
class GrantsSearchTest {

    private HttpServer server;
    private String base;
    private volatile int status = 200;
    private volatile String body = "{}";
    private final List<String> errors = new ArrayList<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/grist", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort() + "/grist?query=x";
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private GrantsSearch search(int numGrants) {
        return new GrantsSearch("Earlham", numGrants, 3, true) {
            @Override
            protected String getRestURI() {
                return base;
            }
        };
    }

    private List<String> ids(GrantsSearch s) {
        return s.getGrants().stream().map(Grant::getId).toList();
    }

    @Test
    void collectsDistinctGrantsUpToTheLimit() {
        body = """
            {"HitCount": 4, "RecordList": {"Record": [
              {"Grant": {"Id": "G1", "Funder": {"Name": "Wellcome"}, "Alias": "W-1"}},
              {"Grant": {"Id": "W-1", "Funder": {"Name": "Wellcome"}, "Alias": ["G1-old"]}},
              {"Grant": {"Id": "G1", "Funder": {"Name": "BBSRC"}}},
              {"Grant": {"Id": "G2", "Funder": {"Name": "BBSRC"}}},
              {"Grant": {"Id": "G3", "Funder": {"Name": "BBSRC"}}}
            ]}}""";

        var s = search(3);
        assertTrue(s.collateResults((level, message) -> errors.add(message), 1));

        // W-1 is G1 under its alias; G1 from a different funder is a different grant;
        // a grant with no Alias is still a grant; G3 is beyond the limit
        assertEquals(List.of("G1", "G1", "G2"), ids(s));
    }

    @Test
    void recordsWithMissingDataAreSkipped() {
        body = """
            {"RecordList": {"Record": [
              {},
              {"Grant": {"Funder": {"Name": "No Id"}}},
              {"Grant": {"Id": "G1"}}
            ]}}""";

        var s = search(5);
        assertFalse(s.collateResults((level, message) -> errors.add(message), 1));   // fewer than asked for, but not an error
        assertEquals(List.of("G1"), ids(s));
    }

    @Test
    void anEmptyOrFailedResponseGivesNoGrants() {
        body = "{\"HitCount\": 0}";
        var s = search(5);
        assertFalse(s.collateResults((level, message) -> errors.add(message), 1));
        assertEquals(List.of(), ids(s));

        status = 503;
        body = "unavailable";
        s = search(5);
        assertFalse(s.collateResults((level, message) -> errors.add(message), 1));
        assertEquals(List.of(), ids(s));
    }
}
