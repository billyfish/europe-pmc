package uk.ac.earlham.pmc;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Searches Europe PMC for the publications funded by a Grant.
 * <p>
 * It can be run in two ways:
 * <ul>
 * <li>{@link #collateResults()}, from BaseSearch, blocks while it pages through the
 * results and adds each new Publication to the Grant until it has enough.</li>
 * <li>{@link #searchAsync(HttpClient, ObjectMapper, int, Duration)} sends a single
 * request without blocking and returns the raw JSON, so that ArticlesSearchSet can
 * run many searches at once.</li>
 * </ul>
 */
public class ArticlesSearch extends BaseSearch {

	private Grant grant;

	/** The number of publications to collect, and the page size to ask Europe PMC for. */
	private int maxNumHits;
	
	//final static private String BASE_URI = "https://www.ebi.ac.uk/europepmc/webservices/rest/search?esultType=core&query=grant_id:" + INV-075495"&r&synonym=false&cursorMark=*&pageSize=25&format=json";
	
	
	
	
	public ArticlesSearch (Grant grant) {
		this (grant, PmcSearch.PUBS_DEFAULT);
	}


	public ArticlesSearch (Grant grant, int maxNumHits) {
		this.grant = grant;
		this.maxNumHits = maxNumHits;
	}


	/** The number of publications that {@link #collateResults()} will collect. */
	public int getMaxNumHits () {
		return this.maxNumHits;
	}


	public void setMaxNumHits (int maxNumHits) {
		this.maxNumHits = maxNumHits;
	}
	

	public String getGrantId () {
		return this.grant.getId ();
	}

	
	public Grant getGrant () {
		return this.grant;
	}

	@Override
	protected String getRestURI () {
		return getRestURI (this.maxNumHits);
	}


	public String getRestURI (int maxNumHits) {
		StringBuilder sb = new StringBuilder ();
		
		sb.append ("https://www.ebi.ac.uk/europepmc/webservices/rest/search?format=json&resultType=core&query=grant_id");

		
		try { 
			sb.append (URLEncoder.encode (":\"", StandardCharsets.UTF_8));
			sb.append (URLEncoder.encode (getGrantId (), StandardCharsets.UTF_8));
			sb.append (URLEncoder.encode ("\"", StandardCharsets.UTF_8));
		} catch (Exception e) {
			
		}
		
		
		sb.append ("&pageSize=");
		sb.append (maxNumHits);
		
		return sb.toString ();
	}


	/**
	 * Each entry in Europe PMC's resultList.result is itself a publication, whereas
	 * BaseSearch expects an array of hits per entry, so wrap it in a one-element array.
	 */
	@Override
	protected JsonNode getHitsNode (JsonNode resultNode) {
		return BaseSearch.MAPPER.createArrayNode ().add (resultNode);
	}


	/**
	 * Adds the publication to the Grant if it isn't already there.
	 *
	 * @return <code>true</code> if it was added.
	 */
	@Override
	protected boolean addResult (JsonNode node) {
		boolean addedFlag = false;
		Publication p = Publication.fromEuropePmc (node);

		if (this.grant.isNewPublication (p)) {
			this.grant.addPublication (p);
			addedFlag = true;
		}

		return addedFlag;
	}


	@Override
	protected boolean hasRequiredNumberOfResults () {
		return (this.grant.getNumberOfPublications () >= this.maxNumHits);
	}


	/**
	 * Sends a GET request to the address from {@link #getRestURI(int)} without
	 * blocking the calling thread.
	 *
	 * @param http The HttpClient to send the request with. It's thread-safe, so share one.
	 * @param mapper The ObjectMapper to parse the response with.
	 * @param maxNumHits Passed on to getRestURI ().
	 * @param timeout How long to wait for the response.
	 * @return A future holding the parsed JSON response. It completes exceptionally with
	 * an IOException if the server returns anything other than HTTP 200 or the body isn't
	 * valid JSON, an HttpTimeoutException if there's no response within the timeout, or an
	 * IllegalArgumentException if getRestURI () doesn't give a valid URI.
	 */
	public CompletableFuture <JsonNode> searchAsync (HttpClient http, ObjectMapper mapper, int maxNumHits, Duration timeout) {
		HttpRequest request;

		String uriStr = null;
		
		try {
			uriStr = getRestURI (maxNumHits);
		} catch (Exception e) {
			throw new CompletionException (new IOException ("Failed to get the Rest URI to call"));
		}
		
		try {
			request = HttpRequest.newBuilder (URI.create (uriStr))
				.timeout (timeout)
				.header ("Accept", "application/json")
				.GET ()
				.build ();
		} catch (IllegalArgumentException e) {
			/* e.g. a grant ID containing a space; report it through the future like any other failure */
			return CompletableFuture.failedFuture (e);
		}

		return http.sendAsync (request, HttpResponse.BodyHandlers.ofString ()).thenApply (response -> {
			if (response.statusCode () != 200) {
				throw new CompletionException (new IOException ("Europe PMC returned HTTP " + response.statusCode () + " for " + request.uri ()));
			}

			try {
				return mapper.readTree (response.body ());
			} catch (JacksonException e) {
				throw new CompletionException (new IOException ("Invalid JSON from Europe PMC for " + request.uri (), e));
			}
		});
	}
}
