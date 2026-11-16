package uk.ac.earlham.pmc;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * A search of Europe PMC for the publications that cite a given grant.
 */
public class ArticlesSearch {

	/** The grant to find the publications for. */
	private Grant grant;
	
	//final static private String BASE_URI = "https://www.ebi.ac.uk/europepmc/webservices/rest/search?esultType=core&query=grant_id:" + INV-075495"&r&synonym=false&cursorMark=*&pageSize=25&format=json";
	
	

	/**
	 * Create a search for the publications that cite a grant.
	 *
	 * @param grant The grant to search for.
	 */
	public ArticlesSearch (Grant grant) {
		this.grant = grant;
	}


	/**
	 * Get the ID of the grant being searched for.
	 *
	 * @return The grant's ID.
	 */
	public String getGrantId () {
		return this.grant.getId ();
	}


	/**
	 * Get the grant being searched for.
	 *
	 * @return The grant.
	 */
	public Grant getGrant () {
		return this.grant;
	}

	/**
	 * Build the Europe PMC REST address to search for the grant's publications.
	 *
	 * @param maxNumHits The maximum number of results to return.
	 * @return The address, with the query from {@link #getQuery()} URL-encoded.
	 */
	public String getRestURI (int maxNumHits) {
		StringBuilder sb = new StringBuilder ();

		sb.append ("https://www.ebi.ac.uk/europepmc/webservices/rest/search?format=json&resultType=core&query=");
		sb.append (URLEncoder.encode (getQuery (), StandardCharsets.UTF_8));
		sb.append ("&pageSize=");
		sb.append (maxNumHits);

		return sb.toString ();
	}


	/**
	 * Build the search query for the grant's ID and any aliases it has, e.g.
	 * <code>grant_id:"G1" OR grant_id:"G1-old"</code>, since a publication may
	 * cite the grant under any of them.
	 *
	 * @return The unencoded query.
	 */
	String getQuery () {
		Set <String> ids = new LinkedHashSet <String> ();

		ids.add (getGrantId ());

		for (String alias : this.grant.getAliases ()) {
			if ((alias != null) && (!alias.isBlank ())) {
				ids.add (alias.strip ());
			}
		}

		StringBuilder sb = new StringBuilder ();

		for (String id : ids) {
			if (sb.length () > 0) {
				sb.append (" OR ");
			}

			/* Escape any quotes so an ID can't end the phrase early */
			sb.append ("grant_id:\"").append (id.replace ("\"", "\\\"")).append ("\"");
		}

		return sb.toString ();
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

		URI uri = request.uri ();

		return http.sendAsync (request, HttpResponse.BodyHandlers.ofString ())
			.thenApply (response -> parseResponse (response, uri, mapper));
	}


	/**
	 * Checks the HTTP status of a response from Europe PMC and parses its body as JSON.
	 * Used by {@link #searchAsync(HttpClient, ObjectMapper, int, Duration)} once the
	 * response has arrived.
	 *
	 * @param response The response from Europe PMC.
	 * @param uri The address that was called, for use in error messages.
	 * @param mapper The ObjectMapper to parse the response with.
	 * @return The parsed JSON response.
	 * @throws CompletionException wrapping an IOException if the status isn't HTTP 200
	 * or the body isn't valid JSON, so that the future completes exceptionally.
	 */
	private static JsonNode parseResponse (HttpResponse <String> response, URI uri, ObjectMapper mapper) {
		if (response.statusCode () != 200) {
			throw new CompletionException (new IOException ("Europe PMC returned HTTP " + response.statusCode () + " for " + uri));
		}

		String responseBody = response.body ();

		try {
			return mapper.readTree (responseBody);
		} catch (JacksonException e) {
			throw new CompletionException (new IOException ("Invalid JSON from Europe PMC for " + uri + " " + responseBody, e));
		}
	}
}
