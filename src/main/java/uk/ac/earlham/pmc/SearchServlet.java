package uk.ac.earlham.pmc;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * {@code GET /search?institution=...&limit=N&publicationsPerGrant=N}
 *
 * Returns JSON. Invalid parameters give HTTP 400 with {"errors": [...]}.
 * Otherwise the response is HTTP 200 and each grant's entry in "publications"
 * has a status; a lookup that failed, or only partly succeeded, carries an
 * "error" with the details instead of failing the whole request.
 */
@WebServlet (name = "search", urlPatterns = "/search", asyncSupported = true)
public class SearchServlet extends HttpServlet {

    private static final System.Logger LOG = System.getLogger (SearchServlet.class.getName ());

    /** How long to wait for all the Europe PMC lookups before Tomcat gives up on the request. */
    static final long ASYNC_TIMEOUT_MS = 120_000;    
   

    /**
     * Initialise the servlet. There is currently nothing to set up.
     */
    @Override
    public void init () {
    }

    /**
     * Handle a search request. The parameters are validated and, if they're
     * valid, the search is run and its results are written out as JSON.
     *
     * @param req The incoming request.
     * @param resp The response to write the JSON to.
     * @throws IOException if the response can't be written.
     */
    @Override
    protected void doGet (HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType ("application/json");
        resp.setCharacterEncoding (StandardCharsets.UTF_8);

        PmcSearch searcher = null;
        try {
        	searcher = PmcSearch.parse (req.getParameterMap ());
        } catch (GrantsSearch.InvalidRequestException e) {
            resp.setStatus (HttpServletResponse.SC_BAD_REQUEST);
            BaseSearch.MAPPER.writeValue (resp.getWriter (), Map.of ("errors", e.errors ()));
            return;
        }
	
        
		if (searcher != null) {
			JsonNode resultsNode = searcher.search ();
			
	        if (resultsNode != null) {        
	        	PrintWriter responseWriter = resp.getWriter (); 
	        	BaseSearch.MAPPER.writeValue (responseWriter, resultsNode);        	
	        }
		}
		
        
    }
    
    
    
    
    
}
