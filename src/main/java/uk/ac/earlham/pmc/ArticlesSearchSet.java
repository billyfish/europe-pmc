package uk.ac.earlham.pmc;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeoutException;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * A set of ArticlesSearch objects whose requests are sent concurrently.
 */
public class ArticlesSearchSet {

	private static final System.Logger LOG = System.getLogger (ArticlesSearchSet.class.getName ());

	/** How many requests may be in flight to Europe PMC at once, unless told otherwise. */
	public static final int DEFAULT_MAX_CONCURRENT = 5;

	

	/** The searches to run. */
	private final List <ArticlesSearch> articleSearches;

	/** The HttpClient used to send every request. */
	private final HttpClient http;

	/** The ObjectMapper used to parse every response. */
	private final ObjectMapper mapper;

	/** The most requests that searchAll () will have in flight at the same time. */
	private final int maxConcurrent;

		
	/**
	 * The outcome of one search. Exactly one of response and error is set.
	 *
	 * @param search The search this is the outcome of.
	 * @param response The parsed JSON response, or <code>null</code> if the search failed.
	 * @param error Why the search failed or timed out, or <code>null</code> if it succeeded.
	 */
	public record Outcome (ArticlesSearch search, JsonNode response, String error) {

		/**
		 * Did the search succeed?
		 *
		 * @return <code>true</code> if the search succeeded, <code>false</code> otherwise.
		 */
		public boolean succeeded () {
			return (this.error == null);
		}


		/**
		 * Get the grant that the search was for.
		 *
		 * @return The grant.
		 */
		public Grant getGrant () {
			return this.search.getGrant ();
		}


		/**
		 * Get the error along with the response, if there is one, for reporting.
		 *
		 * @return The error followed by the pretty-printed response. This is an
		 * empty String if there is neither.
		 */
		public String getFullErrorDetails () {
			StringBuilder sb = new StringBuilder ();
			
			if (this.error != null) {
				sb.append (this.error);
			}
			
			if (this.response != null) {
				sb.append (" ");
				sb.append (this.response.toPrettyString ());
			}
				
			
			return sb.toString ();
		}
	}


	/**
	 * Uses an HttpClient set up for Europe PMC.
	 *
	 * @param mapper The ObjectMapper to parse the responses with.
	 */
	public ArticlesSearchSet (ObjectMapper mapper) {
		this (HttpClient.newBuilder ()
				/* EBI's servers often reset HTTP/2 connections (GOAWAY / STREAM_CLOSED) */
				.version (HttpClient.Version.HTTP_1_1)
				.connectTimeout (BaseSearch.REQUEST_TIMEOUT)
				.followRedirects (HttpClient.Redirect.NORMAL)
				.build (), mapper);
	}


	/**
	 * Allows up to {@link #DEFAULT_MAX_CONCURRENT} requests at once.
	 *
	 * @param http The HttpClient to send the requests with.
	 * @param mapper The ObjectMapper to parse the responses with.
	 */
	public ArticlesSearchSet (HttpClient http, ObjectMapper mapper) {
		this (http, mapper, DEFAULT_MAX_CONCURRENT);
	}


	/**
	 * Create a set of searches that run concurrently.
	 *
	 * @param http The HttpClient to send the requests with.
	 * @param mapper The ObjectMapper to parse the responses with.
	 * @param maxConcurrent The most requests to have in flight at once, so that we
	 * don't swamp Europe PMC. Must be at least 1.
	 * @throws IllegalArgumentException if maxConcurrent is less than 1.
	 */
	public ArticlesSearchSet (HttpClient http, ObjectMapper mapper, int maxConcurrent) {
		if (maxConcurrent < 1) {
			throw new IllegalArgumentException ("maxConcurrent must be at least 1, not " + maxConcurrent);
		}

		this.articleSearches = new ArrayList <ArticlesSearch> ();
		this.http = http;
		this.mapper = mapper;
		this.maxConcurrent = maxConcurrent;
	}

	/**
	 * Uses a new ObjectMapper and an HttpClient set up for Europe PMC.
	 */
	public ArticlesSearchSet () {
		this (JsonMapper.builder ().build ());
	}



	/**
	 * Add a search to run.
	 *
	 * @param search The search to add.
	 */
	public void addArticleSearch (ArticlesSearch search) {
		articleSearches.add (search);
	}


	/**
	 * Add several searches to run.
	 *
	 * @param searches The searches to add.
	 */
	public void addArticleSearches (Collection <ArticlesSearch> searches) {
		articleSearches.addAll (searches);
	}


	/**
	 * Get the searches that will be run.
	 *
	 * @return An unmodifiable copy of the list of searches.
	 */
	public List <ArticlesSearch> getArticleSearches () {
		return List.copyOf (articleSearches);
	}
	
	
	/**
	 * Calls searchAsync () on every ArticlesSearch in articleSearches, with up to
	 * maxConcurrent requests running at the same time, then waits until every one has
	 * completed or timed out. Each request starts as soon as a slot is free, so this takes,
	 * at most, about <code>timeout</code> per batch of maxConcurrent requests.
	 * A failure or timeout only affects that search's Outcome; the others are unaffected.
	 *
	 * @param maxNumHits Passed on to each search's getRestURI ().
	 * @param timeout How long to wait for each response.
	 * @return One Outcome per search, in the same order as articleSearches.
	 */
	public List <Outcome> searchAll (int maxNumHits, Duration timeout) {
		List <CompletableFuture <Outcome>> futures = new ArrayList <> ();
		Semaphore slots = new Semaphore (maxConcurrent);

		for (ArticlesSearch search : articleSearches) {
			CompletableFuture <JsonNode> response;
			boolean gotSlot = false;

			try {
				/* Wait for a free slot; it's given back when this search's future completes */
				slots.acquire ();
				gotSlot = true;
				response = search.searchAsync (http, mapper, maxNumHits, timeout);
			} catch (InterruptedException e) {
				Thread.currentThread ().interrupt ();
				response = CompletableFuture.failedFuture (e);
			} catch (RuntimeException e) {
				response = CompletableFuture.failedFuture (e);
			}

			final boolean heldSlot = gotSlot;

			/*
			 * orTimeout is a backstop in case the HttpRequest timeout doesn't fire, so that
			 * waiting below can never hang. handle () turns success or failure into an Outcome,
			 * so every future in the list completes normally.
			 */
			futures.add (response
				.orTimeout (timeout.toMillis (), java.util.concurrent.TimeUnit.MILLISECONDS)
				.whenComplete ((json, failure) -> {
					if (heldSlot) {
						slots.release ();
					}
				})
				.handle ((json, failure) -> {
					if (failure == null) {
						return new Outcome (search, json, null);
					}

					Throwable cause = unwrap (failure);
					LOG.log (System.Logger.Level.WARNING, "Articles search failed for grant " + search.getGrantId (), cause);
					return new Outcome (search, null, describe (cause, timeout));
				}));
		}

		/* Wait for them all; each completes by its timeout at the latest */
		CompletableFuture.allOf (futures.toArray (new CompletableFuture [0])).join ();

		List <Outcome> outcomes = new ArrayList <Outcome> ();
		for (CompletableFuture <Outcome> future : futures) {
			outcomes.add (future.join ());
		}

		return outcomes;
	}


	/**
	 * Strip any CompletionException or ExecutionException wrappers to get the
	 * exception that actually caused a failure.
	 *
	 * @param t The exception to unwrap.
	 * @return The innermost cause.
	 */
	private static Throwable unwrap (Throwable t) {
		while ((t instanceof CompletionException || t instanceof ExecutionException) && t.getCause () != null) {
			t = t.getCause ();
		}

		return t;
	}


	/**
	 * Turn the cause of a failed search into a message to report.
	 *
	 * @param t The cause of the failure.
	 * @param timeout The timeout that was used, for reporting timeouts.
	 * @return The message.
	 */
	private static String describe (Throwable t, Duration timeout) {
		if (t instanceof TimeoutException || t instanceof HttpTimeoutException) {
			return "No response from Europe PMC within " + timeout.toMillis () + " ms";
		}

		String message = t.getMessage ();
		if (message == null || message.isBlank ()) {
			return "Could not get a response from Europe PMC (" + t.getClass ().getSimpleName () + ")";
		}

		return message;
	}
}
