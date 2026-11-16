package uk.ac.earlham.pmc;

import java.lang.System.Logger;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.exc.JsonNodeException;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.NumericNode;
import tools.jackson.databind.node.ObjectNode;

import uk.ac.earlham.pmc.ArticlesSearchSet.Outcome;
import uk.ac.earlham.pmc.GrantsSearch.InvalidRequestException;


/**
 * Finds the grants held by an institution and then, for each grant, the
 * publications that cite it, collecting any errors along the way.
 */
public class PmcSearch implements ErrorReceiver {

	/** The fewest grants that can be requested. */
	public static final int GRANTS_MIN = 1;
	/** The most grants that can be requested. */
	public static final int GRANTS_MAX = 10;

	/** The number of grants to return if none is given. */
	public static final int GRANTS_DEFAULT = GRANTS_MAX;


	/** The fewest publications per grant that can be requested. */
	public static final int PUBS_MIN = 0;
	/** The most publications per grant that can be requested. */
	public static final int PUBS_MAX = 5;


	/** The number of publications per grant to return if none is given. */
	public static final int PUBS_DEFAULT = PUBS_MAX;


	/** The GET parameter name for the institution to search for. */
	private static final String INSTITUTION_PARAM = "institution";

	/** 
	 * The GET parameter name for the number of grants to return subject 
	 * to the upper limit defined by GRANTS_MAX. 
	 * @see #GRANTS_MAX
	 */
	private static final String GRANTS_PARAM = "limit";

	/** 
	 * The GET parameter name for the number of publications to get 
	 * per grant subject to the upper limit defined by PUBS_MAX. 
	 * @see #PUBS_MAX
	 */
	private static final String PUBS_PARAM = "publicationsPerGrant";
	

	/** The GET parameter name for the page of results to return. */
	private static final String PAGE_PARAM = "page";


	/** How far a search got. */
	enum Status {
		/** The search hasn't completed. */
		STATUS_IDLE,

		/** Every part of the search succeeded. */
		STATUS_FULLY_SUCCEEDED,

		/** Some parts of the search succeeded and some failed. */
		STATUS_PARTIALLY_SUCCEEDED,

		/** The search failed. */
		STATUS_FAILED

	};


	private static final System.Logger LOG = System.getLogger (ArticlesSearchSet.class.getName ());


	/** The institution to search for. */
	private String institution;
	/** The maximum number of grants to return. */
	private int numGrants;
	/** The maximum number of publications to return for each grant. */
	private int numPubs;

	/** The search for the institution's grants. */
	private GrantsSearch grantsSearch;
	
	/* 
	 * Store a list of Errors
	 */
	private List <String> errors;

	/** The outcome of the last call to {@link #run()}. */
	private Status status;

	/** The page of grant results to start from. */
	private int page;


	/**
	 * A command line version for testing
	 * @param args <code>-i institution</code> (required), <code>-g numGrants</code>,
	 * <code>-p publicationsPerGrant</code> and <code>-pg page</code>.
	 */
	public static void main (String [] args) {
		int numGrants = PmcSearch.GRANTS_DEFAULT;
		int numPubs = PmcSearch.PUBS_DEFAULT;
		int page = 1;
		String institution = null;
		
		
		for (int i = 0; i < args.length; ++ i) {
			if ("-i".equals (args [i])) {
				if (++ i < args.length) {
					institution = args [i];					
				} else {
					System.err.println ("Trailing arg for -i");
				}
					
			} else if ("-g".equals (args [i])) {
				if (++ i < args.length) {
					try {
						numGrants = Integer.parseInt (args [i]);
					} catch (NumberFormatException e) {
						System.err.println ("Failed to get grant value from \"" + args [i] + "\"");
					}				
				} else {
					System.err.println ("Trailing arg for -g");
				}
				
			} else if ("-pg".equals (args [i])) {
				if (++ i < args.length) {
					try {
						page = Integer.parseInt (args [i]);
					} catch (NumberFormatException e) {
						System.err.println ("Failed to get page value from \"" + args [i] + "\"");
					}				
				} else {
					System.err.println ("Trailing arg for -pg");
				}

			} else if ("-p".equals (args [i])) {
				if (++ i < args.length) {
					try {
						numPubs = Integer.parseInt (args [i]);
					} catch (NumberFormatException e) {
						System.err.println ("Failed to get publications value from \"" + args [i] + "\"");
					}				
				} else {
					System.err.println ("Trailing arg for -p");
				}

				
			} else {
				System.out.println ("Unknown arg \"" + args [i] + "\" at index " + i);
			}
		}
		
		
		if (institution != null) {		
			PmcSearch searcher = new PmcSearch (institution, numGrants, numPubs, page);
									
			JsonNode node = searcher.search ();
			
			if (node != null) {
				System.out.println (node.toPrettyString ());
			}
			
					
		}

	}
	
		
	/**
	 * Parses and validates the parameters.
	 *
	 * @param params The parametersMap from the incoming Request.
	 * @return A PmcSearch set up with the parameters' values.
	 * @throws InvalidRequestException if any parameters are missing or invalid.
	 */
	public static PmcSearch parse (Map <String, String []> params) throws InvalidRequestException {
		/* Store any errors in a list to report back in the response */
		List <String> errors = new ArrayList <String> ();

		String institution = PmcSearch.getStringParameterValue (params, INSTITUTION_PARAM, true, errors);
		int grants = 0; 
		int pubsPerGrant = 0;
		int page = 1;
		
		/* getStringParameterValue has already recorded why institution is missing */
		if (institution != null) {
			grants = getIntegerParameterValue (params, GRANTS_PARAM, GRANTS_DEFAULT, GRANTS_MIN, GRANTS_MAX, false, errors);
			pubsPerGrant = getIntegerParameterValue (params, PUBS_PARAM, PUBS_DEFAULT, PUBS_MIN, PUBS_MAX, false, errors);
			page = getIntegerParameterValue (params, PAGE_PARAM, 1, 1, Integer.MAX_VALUE, false, errors);
		}

		/* If we have any errors, then bail out and report them */
		if (!errors.isEmpty ()) {
			throw new InvalidRequestException (errors);
		}

		return new PmcSearch (institution, grants, pubsPerGrant, page);
	}


	/**
	 * Run the search and get its results.
	 *
	 * @return The results as JSON, as given by {@link #getAsJSON(ObjectMapper)}.
	 */
	public JsonNode search () {
		Status status = run ();

		JsonNode node = getAsJSON (BaseSearch.MAPPER);

		return node;
	}

	/**
	 * Create a search. Values outside the allowed limits are clamped to them
	 * and a warning is added to the errors.
	 *
	 * @param institution The institution to search for.
	 * @param numGrants The maximum number of grants to return.
	 * @param numPubs The maximum number of publications to return for each grant.
	 * @param page The page of grant results to start from.
	 */
	public PmcSearch (String institution, int numGrants, int numPubs, int page) {
		this.institution = institution;

		this.errors = new ArrayList <String> ();

		
		/* 
		 * make sure that the numbers requested are between our limits
		 */
		if (numGrants > PmcSearch.GRANTS_MAX) {
			addError (System.Logger.Level.WARNING, "Number of Grants requested was too high (" + numGrants + "), using the maximum allowed (" + PmcSearch.GRANTS_MAX + ")");
			numGrants = PmcSearch.GRANTS_MAX;			
		} else if (numGrants < PmcSearch.GRANTS_MIN) {
			addError (System.Logger.Level.WARNING, "Number of Grants requested was too low (" + numGrants + "), using the minimum allowed (" + PmcSearch.GRANTS_MIN + ")");
			numGrants = PmcSearch.GRANTS_MIN;		
		}

		if (numPubs > PmcSearch.PUBS_MAX) {
			addError (System.Logger.Level.WARNING, "Number of Publications per Grant requested was too high (" + numPubs + "), using the maximum allowed (" + PmcSearch.PUBS_MAX + ")");
			numPubs = PmcSearch.PUBS_MAX;			
		} else if (numPubs < PmcSearch.PUBS_MIN) {
			addError (System.Logger.Level.WARNING, "Number of Publications per Grant requested was too low (" + numPubs + "), using the minimum allowed (" + PmcSearch.PUBS_MIN + ")");
			numPubs = PmcSearch.PUBS_MIN;		
		}

		
		if (page < 1) {
			addError (System.Logger.Level.WARNING, "Page must be a positive integer");			
			page = 1;
		}
		
		
		this.numGrants = numGrants;
		this.numPubs = numPubs;
		this.page = page;
		
		boolean usingGristFlag = true;
		this.grantsSearch = new GrantsSearch (institution, numGrants, numPubs, usingGristFlag);
		
	}


	/**
	 * The institution to search for.
	 *
	 * @return the institution.
	 */
	public String getInstitution () {
		return this.institution;
	}


	/**
	 * The maximum number of grants to return.
	 *
	 * @return the number of grants.
	 */
	public int getNumGrants () {
		return this.numGrants;
	}


	/**
	 * The maximum number of publications to return for each grant.
	 *
	 * @return the number of publications.
	 */
	public int getNumPublicationsPerGrant () {
		return this.numPubs;
	}


	/**
	 * Get the results of the search as JSON, with the grants and their
	 * publications under "results", a summary under "metadata" and any
	 * problems under "errors".
	 *
	 * @param mapper The ObjectMapper used to create the JSON.
	 * @return The JSON.
	 */
	public JsonNode getAsJSON (ObjectMapper mapper) {
		ObjectNode thisNode = mapper.createObjectNode ();
		
		ArrayNode resultsNode = mapper.createArrayNode ();		
		thisNode.set ("results", resultsNode);
		
		List <Grant> grants = grantsSearch.getGrants ();
		
		for (Grant g : grants) {
			JsonNode grantNode = g.getAsJSON (mapper);
			
			resultsNode.add (grantNode);
		}
		
		
		ObjectNode metadataNode = mapper.createObjectNode ();
		thisNode.set ("metadata", metadataNode);
		
		metadataNode.put ("institution", this.institution);
		metadataNode.put ("numGrants", this.numGrants);
		metadataNode.put ("numPublicationsPerGrant", this.numPubs);
		metadataNode.put ("status", getStatusAsString ());
		metadataNode.put ("hitCount", grantsSearch.getHitCount ());


		/* Add the summary of the funders */
		Map <String, Integer> fundersMap = grantsSearch.getFundersSummary ();
		
		if (fundersMap != null) {
			ArrayNode fundersNode = mapper.createArrayNode ();
			metadataNode.set ("funders", fundersNode);

			for (Map.Entry <String, Integer> entry : fundersMap.entrySet ()) {
				ObjectNode funderNode = mapper.createObjectNode ();
				
				funderNode.put ("name", entry.getKey ());
				funderNode.put ("count", entry.getValue ());
				
				fundersNode.add (funderNode);				
			}
		}
		
		
		ObjectNode errorsNode = mapper.createObjectNode ();
		thisNode.set ("errors", errorsNode);

		
		ArrayNode errorsListNode = mapper.createArrayNode ();		
		errorsNode.set ("errorsList", errorsListNode);

		for (String errorStr : this.errors) {
			errorsListNode.add (errorStr);
		}
		
		return thisNode;
	}


	/**
	 * Log an error and add it to the list that's returned with the results.
	 *
	 * @param level The severity of the problem.
	 * @param errorStr A description of the problem.
	 */
	public void addError (System.Logger.Level level, String errorStr) {
		LOG.log (level, errorStr);

		errors.add (errorStr);
	}



	/**
	 * Search for the institution's grants and then, concurrently, for the
	 * publications that cite each of them.
	 *
	 * @return Whether all, some or none of the searches succeeded. This is
	 * {@link Status#STATUS_IDLE} if not enough grants were found.
	 */
	public Status run () {
		Status s = Status.STATUS_IDLE;
		
		if (grantsSearch.collateResults (this, this.page)) {
			List <Grant> grants = grantsSearch.getGrants ();
			
			if (grants != null) {
				/*
				 * Now we have the grants we can do an articles search for each.
				 * These can all run in parallel asynchronously
				 */
				ArticlesSearchSet articlesSearchSet = new ArticlesSearchSet ();
				
				for (Grant g : grants) {
					articlesSearchSet.addArticleSearch (g.getSearch ());
				}
				
				/*
				 * Set a timeout of 30 seconds
				 */
				Duration timeout = Duration.ofSeconds (30);
				
				List <Outcome> outcomes = articlesSearchSet.searchAll (numPubs, timeout);
				
				int numSuccessfulOutcomes = 0;
				for (Outcome outcome : outcomes) {
					if (outcome.succeeded ()) {
						++ numSuccessfulOutcomes;
					}
				}
				
				if (numSuccessfulOutcomes == outcomes.size ()) {
					s = Status.STATUS_FULLY_SUCCEEDED;
				} else if (numSuccessfulOutcomes > 0) {
					s = Status.STATUS_PARTIALLY_SUCCEEDED;
				} else {
					s = Status.STATUS_FAILED;
				}
				
				if (s != Status.STATUS_FAILED) {
					Status publicationsStatus = addPublications (outcomes);
				
					/* Let's combine the two statuses */
					if (publicationsStatus == Status.STATUS_FAILED) {
						s = Status.STATUS_PARTIALLY_SUCCEEDED;
					} else if (publicationsStatus == Status.STATUS_PARTIALLY_SUCCEEDED) {
						s = Status.STATUS_PARTIALLY_SUCCEEDED;
					}
				}
			}
			
		}

		this.status = s;
		
		return s;
	}
	


	/**
	 * Attach the publications in each successful Outcome to the Grant whose search
	 * produced it, skipping duplicates and stopping once a grant has numPubs of them.
	 * A failed Outcome is recorded as an error and doesn't affect the other grants.
	 *
	 * @param outcomes The results of the articles searches, one per grant.
	 * @return Whether all, some or none of the searches succeeded.
	 */
	Status addPublications (List <Outcome> outcomes) {
		if (outcomes == null) {
			return Status.STATUS_IDLE;
		}

		int successfulOutcomesCount = 0;

		for (Outcome outcome: outcomes) {
			if (outcome.succeeded ()) {
				JsonNode node = outcome.response ();
				JsonNode hits = node.path ("resultList").path ("result");
				Grant grant = outcome.getGrant ();

				/* get the total number of hits */
				JsonNode hitCountNode = node.get ("hitCount");
				if (hitCountNode != null) {
					if (hitCountNode instanceof NumericNode) {
						try {
							int numHits = ((NumericNode) hitCountNode).intValue ();
							outcome.getGrant ().setNumberOfPublicationHits (numHits);
						} catch (JsonNodeException e) {
							addError (Logger.Level.WARNING, "Failed to covert number of publiction hits to an integer: " + hitCountNode.toPrettyString ());
						}

					} else {
						addError (Logger.Level.WARNING, "hitCountNode is not an number: " + hitCountNode.toPrettyString ());

					}
				}
				
				
				/* did we find the results? */
				if (! (hits.isMissingNode ())) {
					if (hits instanceof ArrayNode) {
										
						ArrayNode arrayNode = ((ArrayNode) hits);
						final int numNodes = arrayNode.size ();
	
						/* loop over the nodes until we have collected the required amount */
						for (int k = 0; (k < numNodes) && (grant.getNumberOfPublications () < numPubs); ++ k) {
							JsonNode n = arrayNode.get (k);
							Publication p = null;
	
							try {
								p = BaseSearch.MAPPER.treeToValue (n, Publication.class);
							} catch (JacksonException je) {
								addError (Logger.Level.WARNING, "Failed to create Publication from: " + n.toString () + ", exception: " + je);
							}
	
							if ((p != null) && grant.isNewPublication (p)) {
								grant.addPublication (p);
							}
						}

					} else {
						addError (Logger.Level.WARNING, "Article results are not an array: " + hits.toPrettyString ());
					}

				} else {
					addError (Logger.Level.WARNING, "Failed to get list of Article results from: " + node.toPrettyString ());
				}

				++ successfulOutcomesCount;

			} else {
				addError (System.Logger.Level.WARNING, outcome.getFullErrorDetails ());
			}
		}

		Status status;

		if (successfulOutcomesCount == outcomes.size ()) {
			/* we fully succeeded */
			status = Status.STATUS_FULLY_SUCCEEDED;
		} else if (successfulOutcomesCount > 0) {
			/* partial success */
			status = Status.STATUS_PARTIALLY_SUCCEEDED;
		} else {
			/* nothing succeeded */
			status = Status.STATUS_FAILED;
		}

		return status;
	}


	/**
	 * Get the errors and warnings raised so far.
	 *
	 * @return An unmodifiable copy of the errors.
	 */
	List <String> getErrors () {
		return List.copyOf (this.errors);
	}

	/**
	 * Get the status of the last run as a human-readable String.
	 *
	 * @return The status, e.g. "succeeded" or "partially succeeded".
	 */
	private String getStatusAsString () {
		String statusStr = "";
		
		switch (this.status) {
			case STATUS_IDLE:
				statusStr = "idle";
				break;
				
			case STATUS_FULLY_SUCCEEDED:
				statusStr = "succeeded";
				break;

			case STATUS_PARTIALLY_SUCCEEDED:
				statusStr = "partially succeeded";
				break;

			case STATUS_FAILED:
				statusStr = "failed";
				break;

		}
		
		return statusStr;
	}
	
	
	/** 
	 * Get a String parameter from the Request's ParameterMap
	 * 
	 * @param params The Request's parameters.
	 * @param paramName The name of the Parameter to get the value for.
	 * @param requiredFlag If this is <code>true</code>, then if this Parameter is missing, an entry 
	 * will be added to list of errors.
	 * @param errors A List where are any errors will be added.
	 * @return The Parameter value, this can be <code>null</code>.
	 */
	private static String getStringParameterValue (Map <String, String []> params, String paramName, boolean requiredFlag, List <String> errors) {        
		String res = null;
		String [] paramValues = params.get (paramName);

		if (paramValues != null) {
			if (paramValues.length == 1) {
				String paramValue = paramValues [0];

				/* Has the parameter been set? */
				if ((paramValue != null) && (!paramValue.isBlank ())) {
					res = paramValue.strip ();
				} else if (!requiredFlag) {

				} else {
					errors.add ("'" + paramName + "' has no value");       		            	
				}


			} else {
				errors.add ("'" + paramName + "' should only have 1 value, " + paramValues.length + " have been set");
			}

		} else if (requiredFlag) {
			errors.add ("'" + paramName + "' is required");
		}

		return res;
	}


	/** 
	 * Get an integer parameter from the Request's ParameterMap
	 * 
	 * @param params The Request's parameters.
	 * @param paramName The name of the Parameter to get the value for.
	 * @param defaultValue The default value for the Parameter if there isn't a value in the Request's parameters.
	 * @param minValue The minimum value that the Parameter can take.
	 * @param maxValue The maximum value that the Parameter can take. Use Integer.MAX_VALUE for no upper limit.
	 * @param requiredFlag If this is <code>true</code>, then if this Parameter is missing, an entry 
	 * will be added to list of errors.
	 * @param errors A List where are any errors will be added.
	 * @return The Parameter value, or defaultValue if it's missing or isn't an integer.
	 */
	private static int getIntegerParameterValue (Map <String, String []> params, String paramName, int defaultValue, int minValue, int maxValue, boolean requiredFlag, List <String> errors) {
		int res = defaultValue;

		String paramValue = getStringParameterValue (params, paramName, requiredFlag, errors);

		if (paramValue != null) {
			try {
				int value = Integer.parseInt (paramValue);

				if (value < minValue || value > maxValue) {
					if (maxValue == Integer.MAX_VALUE) {
						/* there's no real upper limit, so don't quote one */
						errors.add ("'" + paramName + "' must be " + minValue + " or more");
					} else {
						errors.add ("'" + paramName + "' must be between " + minValue + " and " + maxValue);
					}
				}
				return value;

			} catch (NumberFormatException e) {
				errors.add ("'" + paramName + "' must be an integer");
			}

		}

		return res;
	}

	
}
