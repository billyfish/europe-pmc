package uk.ac.earlham.pmc;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;



/**
 * The base class for searches that page through the results of a Europe PMC
 * REST API until they have collected enough of them. Subclasses say where to
 * find the results in the JSON and what to do with each one.
 */
abstract class BaseSearch {

	private static final System.Logger LOG = System.getLogger (BaseSearch.class.getName ());

	/** How long run () waits for a response. */
	public static final Duration REQUEST_TIMEOUT = Duration.ofSeconds (30);

	/** Thread-safe, so shared by all GrantSearch objects. */
	private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder ()
			/* EBI's servers often reset HTTP/2 connections (GOAWAY / STREAM_CLOSED) */
			.version (HttpClient.Version.HTTP_1_1)
			.connectTimeout (Duration.ofSeconds (10))
			.followRedirects (HttpClient.Redirect.NORMAL)
			.build ();	

	
	/** The parsed response from the last successful call to run (). */
	private JsonNode results;

	/** Why the last call to run () failed. */
	private String error;

	
    /** Thread-safe and expensive to create, so shared. Also use it to parse Europe PMC responses. */
    static final ObjectMapper MAPPER = JsonMapper.builder ().build ();

	

	/**
	 * Add a single result to this search's list of results.
	 *
	 * @param node The JSON for the result.
	 * @param errorReceiver Where to report any problems.
	 * @return <code>true</code> if the result was added, <code>false</code> if it
	 * was invalid or already on the list.
	 */
	abstract protected boolean addResult (JsonNode node, ErrorReceiver errorReceiver);


	/**
	 * Have we collected as many results as were asked for?
	 *
	 * @return <code>true</code> if we have enough results, <code>false</code> otherwise.
	 */
	abstract protected boolean hasRequiredNumberOfResults ();

	/**
	 * Get the REST address to call, without the page parameter, which
	 * {@link #collateResults(ErrorReceiver, int)} adds.
	 *
	 * @return The address.
	 */
	abstract protected String getRestURI ();

	
	/**
	 * Get the JsonNode of interest for a given result from the resultsList.
	 *  
	 * @param resultNode The JsonNode on the resultsList
	 * @return The JsonNode of interest or <code>null</code> upon error.
	 */
	abstract protected JsonNode getHitsNode (JsonNode resultNode);
	
	
	/**
	 * Get the JsonNode for the array of results from the root node.
	 *  
	 * @param rootNode The root node of the response.
	 * @return The JsonNode of interest or <code>null</code> upon error.
	 */
	abstract protected JsonNode getResultsNode (JsonNode rootNode);


	
	/**
	 * Parse the metadata from the search response
	 *  
	 * @param rootNode The root node of the response.
	 * @param pageNumber The number of the current results page.
	 * @param errorReceiver Where to report any problems.
	 * @throws IllegalArgumentException if the hit count is missing from the JSON
	 */
	abstract protected void parseMetadata (JsonNode rootNode, int pageNumber, ErrorReceiver errorReceiver);

	

	/**
	 * Parse a set of results. This is not implemented yet.
	 *
	 * @param results The results to parse.
	 * @return Always <code>false</code>.
	 */
	public boolean parseResults (JsonNode results) {
		return false;
	}

	/**
	 * Call the REST API one page at a time, adding each result with
	 * {@link #addResult(JsonNode, ErrorReceiver)}, until we have the required
	 * number of results or have made 10 more calls after the first.
	 * A page that fails to load is skipped.
	 *
	 * @param errorReceiver Where to report any problems.
	 * @param startPage The page of results to start from.
	 * @return <code>true</code> if we got the required number of results,
	 * <code>false</code> otherwise.
	 */
	public boolean collateResults (ErrorReceiver errorReceiver, int startPage) {
		String uri = getRestURI ();

		/* 
		 * Rather than make indefinite calls, let's stop after a 
		 * a sufficient number of iterations if we're yet to get 
		 * enough grants
		 */
	
		int maxNumCalls = 10 + startPage;
	
		while ((startPage <= maxNumCalls) && (!hasRequiredNumberOfResults ())) {

			if (run (uri + "&page=" + startPage)) {

				parseMetadata (results, startPage, errorReceiver);
				
				JsonNode hits = getResultsNode (this.results);

				if (!hits.isMissingNode ()) {
					/* we have the results */

					if (hits.isArray ()) {
						ArrayNode hitsArray = (ArrayNode) hits;
						int numHits = hitsArray.size ();
						
						for (int i = 0; i < numHits; ++ i) {
							JsonNode node = getHitsNode (hitsArray.get (i));   

							if (! (node.isMissingNode ())) {
								if (node.isArray ()) {
									ArrayNode an = (ArrayNode) node;
									
									final int numNodes = an.size ();
									
									for (int j = 0; j < numNodes; ++ j) {
										JsonNode n = an.get (j);

										if (addResult (n, errorReceiver)) {
											if (hasRequiredNumberOfResults ()) {
												/* 
												 * we have enough results so exit 
												 * the for loops
												 */
												i = numHits;
												j = numNodes;
											}
										}

									}
									
									
								} else if (node.isObject ()) {
										
									if (addResult (node, errorReceiver)) {
										if (hasRequiredNumberOfResults ()) {
											/* 
											 * we have enough results so exit 
											 * the for loops
											 */
											i = numHits;
										}
									}
								} else {
									/* error */
								}
							}							
						}
					}
				}

			} else {

			}    		

			++ startPage;
		}


		return hasRequiredNumberOfResults ();
	}

	
	

	/**
	 * Calls the address from {@link #getRestURI()} and parses the JSON response.
	 * Blocks until the response arrives or the request times out.
	 *
	 * @param uriStr The address to call.
	 * @return <code>true</code> if the call succeeded, in which case the results field
	 * holds the parsed response. If it returns <code>false</code>, the error field
	 * says why.
	 */
	public boolean run (String uriStr) {

		this.results = null;
		this.error = null;

		try {
			URI uri = URI.create (uriStr); 
			HttpRequest request = HttpRequest.newBuilder (uri)
					.timeout (REQUEST_TIMEOUT)
					.header ("Accept", "application/json")
					.GET ()
					.build ();

			HttpResponse <String> response = HTTP_CLIENT.send (request, HttpResponse.BodyHandlers.ofString ());

			if (response.statusCode () != 200) {
				this.error = "HTTP " + response.statusCode () + " from " + uriStr;
			} else {
				this.results = BaseSearch.MAPPER.readTree (response.body ());
			}

		} catch (IllegalArgumentException e) {
			/* URI.create () rejects malformed addresses, e.g. ones without a scheme or with unencoded spaces */
			this.error = "Invalid URI '" + uriStr + "': " + e.getMessage ();
		} catch (JacksonException e) {
			this.error = "Invalid JSON from " + uriStr + ": " + e.getOriginalMessage ();
		} catch (HttpTimeoutException e) {
			this.error = "No response from " + uriStr + " within " + REQUEST_TIMEOUT.toSeconds () + " seconds";
		} catch (IOException e) {
			this.error = "Could not call " + uriStr + ": " + (e.getMessage () != null ? e.getMessage () : e.getClass ().getSimpleName ());
		} catch (InterruptedException e) {
			/* Restore the flag so whoever is waiting on this thread sees it */
			Thread.currentThread ().interrupt ();
			this.error = "Interrupted while calling " + uriStr;
		}

		if (this.error != null) {
			LOG.log (System.Logger.Level.WARNING, this.error);
			return false;
		}

		return true;
	}

	
	
	
}
