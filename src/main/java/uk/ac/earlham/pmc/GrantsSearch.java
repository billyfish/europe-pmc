package uk.ac.earlham.pmc;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.OptionalInt;


import tools.jackson.databind.JsonNode;
import tools.jackson.databind.exc.JsonNodeException;
import tools.jackson.databind.node.NumericNode;
import tools.jackson.databind.node.StringNode;


/**
 * This is the class that does the initial search for grants 
 * by searching for a given affiliation.
 */
public class GrantsSearch extends BaseSearch {

	/** The institution to search for. */
	private String institution;

	/** The maximum number of grants to return. */
	private int numGrants;

	/** The maximum number of publications to return for each grant. */
	private int numPublicationsPerGrant;

	/*
	 * The Grants returned from the search up to a maximum number
	 * set by numGrants.
	 */
	private List <Grant> grants;

	/** The total number of hits reported by Europe PMC. */
	private int hitCount;
	
	
	/* 
	 * Initially when testing, I couldn't get the documented GristAPI service to load as I think the public
	 * facing part of it was turned off so I initially used the general search API instead as a workaround.
	 * Rather than remove it, I've left it in as an alternative option.  
	 */
	private static final String GENERAL_SEARCH_API = "https://www.ebi.ac.uk/europepmc/webservices/rest/search?";

	private static final String GRIST_API = "https://www.ebi.ac.uk/europepmc/GristAPI/rest/get/";


	/** <code>true</code> to use the Grist API, <code>false</code> to use the general search API. */
	private boolean usingGristFlag;


	/**
	 * Create a search for the grants held by an institution.
	 *
	 * @param institutionName The institution to search for.
	 * @param grants The maximum number of grants to return.
	 * @param pubs The maximum number of publications to return for each grant.
	 * @param gristFlag <code>true</code> to use the Grist API, <code>false</code>
	 * to use the general search API.
	 */
	public GrantsSearch (String institutionName, int grants, int pubs, boolean gristFlag) {
		this.institution = institutionName;
		this.numGrants = grants;
		this.numPublicationsPerGrant = pubs;
		this.grants = new ArrayList <Grant> ();
				
		this.hitCount = 0;
		
		this.usingGristFlag = gristFlag;
	}


	/** 
	 * The institution to search for. 
	 * 
	 *  @return the institution.
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
		return this.numPublicationsPerGrant;
	}

	
	/**
	 * Get the number of hits from our GrantsSearch. This is not the number of items
	 * on our results list; it's the total number of hits reported by the EuropePMC rest API.
	 * @return The number of hits, or 0 if it hasn't been retrieved.
	 */
	public int getHitCount () {
		return this.hitCount;
	}
	
	
	/**
	 * Have we got the maximum number of grants?
	 * 
	 * @return <code>true</code> if we have the number of grants that the user
	 * requested, <code>false</code> otherwise.
	 */
	protected boolean hasRequiredNumberOfResults () {
		return (this.grants.size () == this.numGrants);
	}

	
	/**
	 * Get a map where the keys are the funder names and the values are the number of
	 * grants that they are funding. This is purely representing the values on our 
	 * results list, not the EuropePMC server as a whole.
	 * 
	 * @return the map.
	 */
	public Map <String, Integer> getFundersSummary () {
		Map <String, Integer> fundersMap = new HashMap <String, Integer> ();
		
		for (Grant g : this.grants) {
			String funder = g.getFunder ();
			Integer value = fundersMap.get (funder);
			
			
			if (value == null) {
				value = Integer.valueOf (1);
			} else {
				value = Integer.valueOf (value.intValue () + 1);
			}
			
			fundersMap.put (funder, value);
		}
		
		return fundersMap;
	}
	

	/** Thrown when one or more parameters are missing or invalid. */
	public static class InvalidRequestException extends Exception {
		/** What was wrong with the request. */
		private final List <String> errors;

		/**
		 * Create the exception with the problems found in a request.
		 *
		 * @param errors What was wrong with the request.
		 */
		InvalidRequestException (List <String> errors) {
			super (String.join ("; ", errors));
			this.errors = List.copyOf (errors);
		}

		/**
		 * Get what was wrong with the request.
		 *
		 * @return An unmodifiable list of the errors.
		 */
		public List <String> errors () {
			return errors;
		}

	}

	
	/**
	 * Is the given grant already on our list?
	 * 
	 * @param grants The list of Grants to check.
	 * @param grant The Grant to search for.
	 * @return <code>true</code> if the given Grant isn't on the list, <code>false</code> otherwise
	 */
	private boolean isNewGrant (List <Grant> grants, Grant grant) {    	
		for (Grant g : grants) {
			if (g.equals (grant)) {
				return false;
			}
		}

		return true;
	}
	

	/**
	 * Get the grants found so far.
	 *
	 * @return The grants.
	 */
	public List <Grant> getGrants () {
		return this.grants;
	}



	/**
	 * Create a Grant from the JSON and add it to our list unless it's already on it.
	 *
	 * @param node The JSON for the grant.
	 * @param errorReceiver Where to report any problems.
	 * @return <code>true</code> if the Grant was added, <code>false</code> otherwise.
	 */
	protected boolean addResult (JsonNode node, ErrorReceiver errorReceiver) {
		boolean addedFlag = false;
		Grant g;
		
		if (this.usingGristFlag) {
			g = Grant.fromGristJSON (node, this.institution);
		} else {
			g = Grant.fromPMCJSON (node, this.institution);
		}
		
		if (g != null) {
			if (isNewGrant (this.grants, g)) {
				this.grants.add (g);
				addedFlag = true;
			}
		}

		return addedFlag;
	}


	/**
	 * Get the grant or grants from a single result.
	 *
	 * @param resultNode The JSON for the result.
	 * @return The Grant node for the Grist API, or grantsList.grant for the
	 * general search API. This is a missing node if it can't be found.
	 */
	protected JsonNode getHitsNode (JsonNode resultNode) {

		JsonNode node;
		
		if (this.usingGristFlag) {			
			node = resultNode.path ("Grant");
		} else {
			node = resultNode.path ("grantsList").path ("grant");			
		}
		
		return node;		
	}


	/**
	 * Get the array of results from the response.
	 *
	 * @param rootNode The root node of the response.
	 * @return RecordList.Record for the Grist API, or resultList.result for the
	 * general search API. This is a missing node if it can't be found.
	 */
	protected JsonNode getResultsNode (JsonNode rootNode) {
		JsonNode node;
		
		if (this.usingGristFlag) {			
			node = rootNode.path ("RecordList").path ("Record");
		} else {
			node = rootNode.path ("resultList").path ("result");			
		}
		
		return node;
	}
	


	/**
	 * Store the total number of hits from the response, if we haven't already got it.
	 *
	 * @param rootNode The root node of the response.
	 * @param pageNumber The number of the current results page.
	 * @param errorReceiver Where to report the hit count being missing or invalid.
	 */
	protected void parseMetadata (JsonNode rootNode, int pageNumber, ErrorReceiver errorReceiver) {
		if (this.hitCount == 0) {
			try {
				this.hitCount = setHitCountFromResponse (rootNode);
			} catch (IllegalArgumentException iae) {
				errorReceiver.addError (System.Logger.Level.ERROR, iae.toString ());
			}
		}
  	}


	/**
	 * Read the total number of hits from the response. It may be either a
	 * number or a String holding one.
	 *
	 * @param rootNode The root node of the response.
	 * @return The number of hits.
	 * @throws IllegalArgumentException if the hit count is missing or isn't an integer.
	 */
	private int setHitCountFromResponse (JsonNode rootNode) throws IllegalArgumentException {
		int hits = 0;
		String errorStr = null;
		JsonNode node;
		String nodeName;
		
		if (this.usingGristFlag) {		
			nodeName = "HitCount";
			node = rootNode.path (nodeName);						
		} else {
			nodeName = "hitCount";
			node = rootNode.path (nodeName);
		}

		if (! (node.isMissingNode ())) {
			if (node instanceof NumericNode) {
				OptionalInt oi = ((NumericNode) node).asIntOpt ();
				
				if (oi.isPresent ()) {
					try {
						hits = oi.orElseThrow ();						
					} catch (NoSuchElementException e) {
						errorStr = "Node with name " + nodeName + " has NoSuchElementExcption " + e;							
					}
				} else {
					errorStr = "Node with name " + nodeName + " for number of results is not set";
				}
				
			} else if (node instanceof StringNode) {
				try { 
					String hitsStr = ((StringNode) node).stringValue ();
					
					hits = Integer.parseInt (hitsStr);
					
				} catch (JsonNodeException jne) {
					errorStr = "Failed to get string value with name " + nodeName + " for number of results";
				} catch (NumberFormatException nfe) {
					errorStr = "Failed to convert string value with name " + nodeName + " to an integer for number of results";						
				}
			}
			
		} else {
			errorStr = "Failed to find node with name " + nodeName + " for number of results";
		}

		
		
		if (errorStr != null) {
			throw new IllegalArgumentException (errorStr);
		}
		
		
		return hits;
	}

	


	/**
	 * Build the address to search for grants by affiliation. If the institution's
	 * name has an alternative form, e.g. "University of X" and "X University",
	 * both are searched for.
	 *
	 * @return The address.
	 */
	protected String getRestURI () {
		String restUriStr = null;
		StringBuilder sb = new StringBuilder ();
		

		if (this.usingGristFlag) {
			sb.append (GrantsSearch.GRIST_API);
		} else {
			sb.append (GrantsSearch.GENERAL_SEARCH_API);			
		}

		sb.append ("query=");
		
		
		boolean addedQueriesFlag = false;
		
		if (addEncodedQuery (this.institution, sb)) {
		
			
			/* 
			 * Map queries like "X university" to include ""University of X"
			 */
			String altName = InstitutionNameNormaliser.getAlternativeName (this.institution);
			
			if (! (altName.equals (this.institution))) {
				
				sb.append (URLEncoder.encode (" OR ", StandardCharsets.UTF_8));
				
				if (addEncodedQuery (altName, sb)) {
					addedQueriesFlag = true;
				}
			} else {
				addedQueriesFlag = true;
			}
			
			addedQueriesFlag = true;
		}
			
		if (addedQueriesFlag) {
			sb.append ("&resultType=core&format=json");
			restUriStr = sb.toString ();
		}
		
		return restUriStr;
	}


	/**
	 * Append a URL-encoded <code>aff:"value"</code> affiliation query.
	 *
	 * @param value The affiliation to search for.
	 * @param sb The StringBuilder to append to.
	 * @return Always <code>true</code>.
	 */
	private boolean addEncodedQuery (String value, StringBuilder sb) {
		boolean successFlag = true;
		
		sb.append ("aff");
		
		sb.append (URLEncoder.encode (":\"", StandardCharsets.UTF_8));			
		sb.append (URLEncoder.encode (value, StandardCharsets.UTF_8));
		sb.append (URLEncoder.encode ("\"", StandardCharsets.UTF_8));
		
		return successFlag;
	}

}
