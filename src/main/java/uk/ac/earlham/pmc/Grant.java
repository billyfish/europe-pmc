package uk.ac.earlham.pmc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.exc.JsonNodeException;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.StringNode;

/**
 * A grant held by an institution, along with the publications that cite it.
 */
public class Grant {

	/** The grant's ID. */
	private String id;

	/** The name of the grant's funder. */
	private String funder;

	/** The other IDs this grant is known by. */
	private List <String> aliases;

	/** The institution that holds the grant. */
	private String institution;

	/** The publications found that cite this grant. */
	private List <Publication> publications;

	/** The search for the publications that cite this grant. */
	private ArticlesSearch search;


	/** The total number of publications that Europe PMC says cite this grant. */
	private int numberOfPublicationHits;

	/** The value of numberOfPublicationHits before it has been set. */
	public static final int NUM_HITS_UNSET = -1;


	/**
	 * Create a Grant that has several aliases.
	 *
	 * @param id The grant's ID.
	 * @param funder The name of the grant's funder.
	 * @param institution The institution that holds the grant.
	 * @param aliases The other IDs the grant is known by.
	 */
	public Grant (String id, String funder, String institution, List <String> aliases) {
		this (id, funder, institution);

		for (String alias : aliases) {
			this.aliases.add (alias);
		}
	}


	/**
	 * Create a Grant that has a single alias.
	 *
	 * @param id The grant's ID.
	 * @param funder The name of the grant's funder.
	 * @param institution The institution that holds the grant.
	 * @param alias The other ID the grant is known by.
	 */
	public Grant (String id, String funder, String institution, String alias) {
		this (id, funder, institution);

		this.aliases.add (alias);
	}


	/**
	 * Create a Grant with no aliases.
	 *
	 * @param id The grant's ID.
	 * @param funder The name of the grant's funder.
	 * @param institution The institution that holds the grant.
	 */
	private Grant (String id, String funder, String institution) {
		this.id = id;
		this.funder = funder;
		this.institution = institution;		
		
		this.search = new ArticlesSearch (this);

		this.publications = new ArrayList <Publication> ();

		this.aliases = new ArrayList <String> ();		

		this.numberOfPublicationHits = Grant.NUM_HITS_UNSET;
	}


	/**
	 * Set the total number of publications that Europe PMC says cite this grant.
	 *
	 * @param numHits The number of publications. Negative values are ignored.
	 */
	public void setNumberOfPublicationHits (int numHits) {
		if (numHits >= 0) {
			this.numberOfPublicationHits = numHits;
		}
	}


	/**
	 * Get the total number of publications that Europe PMC says cite this grant.
	 * This can be more than the number of publications we have collected.
	 *
	 * @return The number of publications, or {@link #NUM_HITS_UNSET} if it hasn't been set.
	 */
	public int getNumberOfPublicationHits () {
		return this.numberOfPublicationHits;
	}


	/**
	 * Has the total number of publications that cite this grant been set?
	 *
	 * @return <code>true</code> if it has been set, <code>false</code> otherwise.
	 */
	public boolean hasNumberOfPublicationHits () {
		return (this.numberOfPublicationHits != Grant.NUM_HITS_UNSET);
	}



	/**
	 * Get the name of the grant's funder.
	 *
	 * @return The funder, which may be <code>null</code>.
	 */
	public String getFunder () {
		return this.funder;
	}



	/**
	 * Get the grant's ID.
	 *
	 * @return The ID.
	 */
	public String getId () {
		return this.id;
	}

	/**
	 * The other IDs this grant is known by.
	 *
	 * @return An unmodifiable view of the aliases.
	 */
	public List <String> getAliases () {
		return Collections.unmodifiableList (this.aliases);
	}


	/**
	 * Get the number of publications we have collected for this grant.
	 *
	 * @return The number of publications.
	 */
	public int getNumberOfPublications () {
		return this.publications.size ();
	}

	/**
	 * Get the value of a child of a JSON node as a String.
	 *
	 * @param node The parent node.
	 * @param key The name of the child.
	 * @return The child's value, or <code>null</code> if there's no such child.
	 */
	private static String getNodeStringValue (JsonNode node, String key) {
		String value = null;
		JsonNode temp = node.get (key);
		
		if (temp != null) {
			value = temp.asString ();
		}
		
		return value;
	}

	/**
	 * Create a Grant from one entry of a grantsList in a Europe PMC search response.
	 *
	 * @param node The JSON for the grant.
	 * @param institution The institution that holds the grant.
	 * @return The new Grant, or <code>null</code> if the JSON has no grantId.
	 * @throws JsonNodeException if a value can't be read from the JSON.
	 */
	public static Grant fromPMCJSON (JsonNode node, String institution) throws JsonNodeException {
		Grant grant = null;
		String id = Grant.getNodeStringValue (node, "grantId");

		if (id != null) {
			String funder = Grant.getNodeStringValue (node, "agency");
			String acronym = Grant.getNodeStringValue (node, "acronym");

			
			grant = new Grant (id, funder, institution);
		} else {

		}

		return grant;
	}


	/**
	 * Create a Grant from a Grant entry in a Europe PMC Grist API response,
	 * including any aliases it has.
	 *
	 * @param node The JSON for the grant.
	 * @param institution The institution that holds the grant.
	 * @return The new Grant, or <code>null</code> if the JSON has no Id or its
	 * Alias is of an unexpected type.
	 * @throws JsonNodeException if a value can't be read from the JSON.
	 */
	public static Grant fromGristJSON (JsonNode node, String institution) throws JsonNodeException {
		Grant grant = null;
		String id = Grant.getNodeStringValue (node, "Id");
		String funder = null;
		
		JsonNode tempNode = node.get ("Funder");
		if (tempNode != null) {
			funder = Grant.getNodeStringValue (tempNode, "Name");
		}
		
		if (id != null) {			
			tempNode = node.get ("Alias");
			
			if (tempNode != null) {
				
				if (tempNode.isArray ()) {
					ArrayNode aliasesNode = (ArrayNode) tempNode;
					
					int numAliases = aliasesNode.size ();
					List <String> aliases = new ArrayList <String> ();
					
					for (int i = 0; i < numAliases; ++ i) {
						aliases.add (aliasesNode.get (i).asString ());
					}
				
					grant = new Grant (id, funder, institution, aliases);
				} else if (tempNode instanceof ObjectNode) {
					String alias = ((ObjectNode) tempNode).asString ();

					grant = new Grant (id, funder, institution, alias);
					
				} else if (tempNode instanceof StringNode) {
					StringNode sn = (StringNode) tempNode;
					
					String alias = sn.asString ();

					grant = new Grant (id, funder, institution, alias);
				} else {
					
				}
			} else {
				/* Most grants don't have any aliases */
				grant = new Grant (id, funder, institution);
			}
		} else {
			
		}
		
		return grant;
	}


	/**
	 * Get this Grant, along with its funder and publications, as JSON.
	 *
	 * @param mapper The ObjectMapper used to create the JSON.
	 * @return The JSON representation of this Grant.
	 */
	public JsonNode getAsJSON (ObjectMapper mapper) {
		ObjectNode thisNode = mapper.createObjectNode ();

		
		ObjectNode orgNode = mapper.createObjectNode ();
		
		orgNode.put ("@type", "https://schema.org/Organization");
		orgNode.put ("funder", this.funder);		

		thisNode.set ("org", orgNode);
		
		thisNode.put ("so:identifier", this.id);
		
		ArrayNode pubsNode = mapper.createArrayNode ();
		
		thisNode.set ("publications", pubsNode);
		
		for (Publication p : this.publications) {
			ObjectNode node = p.getAsJSON (mapper);
			
			pubsNode.add (node);
		}
		
		
   
		return thisNode;
	}
	
	
	/**
	 * Grant IDs may not be globally unique across funders, so two Grants are
	 * equal if the ID or any alias of one matches the ID or any alias of the
	 * other, and they have the same funder.
	 *
	 * @param o The object to compare with.
	 * @return <code>true</code> if the given object is a matching Grant,
	 * <code>false</code> otherwise.
	 */
	public boolean equals (Object o) {
		boolean equalsFlag = false;
		
		if (o == this) {
			equalsFlag = true;
		} else if (o instanceof Grant) {
			Grant otherGrant = (Grant) o;
			boolean matchedIdFlag = false;
			
			
			matchedIdFlag = isMatchingIdOrAlias (otherGrant.id);
			
			/* 
			 * In production, would use a Map or similar to stop having 
			 * so many pairwise comparisons if the number of aliases can 
			 * be large.
			 */
			if (!matchedIdFlag) {
				final int numOtherAliases = otherGrant.aliases.size ();
				
				for (int i = 0; i < numOtherAliases; ++ i) {
					if (isMatchingIdOrAlias (otherGrant.aliases.get (i))) {
						matchedIdFlag = true;
						i = numOtherAliases;
					}
				}
			}

			if (matchedIdFlag) {
				int res = compareNullableStrings (this.funder, otherGrant.funder);
				
				if (res == 0) {
					equalsFlag = true;
				}
			}
		}
		
		return equalsFlag;		
	}


	/**
	 * Add a publication that cites this grant.
	 *
	 * @param p The publication to add.
	 */
	public void addPublication (Publication p) {
		this.publications.add (p);
	}


	/**
	 * Get the search for the publications that cite this grant.
	 *
	 * @return The search.
	 */
	public ArticlesSearch getSearch () {
		return search;
	}
	
	
	/**
	 * Is the given Publication already on our list?
	 * 
	 * @param publication The Publication to search for.
	 * @return <code>true</code> if the given Publication isn't on the list, <code>false</code> otherwise
	 */
	public boolean isNewPublication (Publication publication) {    	
		for (Publication p : this.publications) {
			if (p.equals (publication)) {
				return false;
			}
		}

		return true;
	}
	
	
	
	/**
	 * Compare two Strings taking into account that either or both 
	 * of them could be <code>null</code>.
	 * @param s0 The first String.
	 * @param s1 The second String.
	 * @return A negative value if the first String is lexigraphically before the second, 
	 * 0 if they’re equal and positive otherwise.
	 */
	private int compareNullableStrings (String s0, String s1) {
		int res = 0;
		
		if (s0 != null) {
			if (s1 != null) {
				res = s0.compareTo (s1);
			} else {
				res = -1;
			}
		} else { 
			if (s1 != null) {
				res = 1;	
			} 
		}
		
		return res;
	}
	

	/**
	 * Does the given ID match this grant's ID or any of its aliases?
	 *
	 * @param otherId The ID to check.
	 * @return <code>true</code> if it matches, <code>false</code> otherwise.
	 */
	private boolean isMatchingIdOrAlias (String otherId) {
		boolean matchFlag = false;
		
		if (compareNullableStrings (this.id, otherId) == 0) {
			matchFlag = true;
		} else {
			final int numAliases = aliases.size ();
			
			for (int i = 0; i < numAliases; ++ i) {
				if (compareNullableStrings (aliases.get (i), otherId) == 0) {
					matchFlag = true;
					i = numAliases;
				}
			}
		}
		
		return matchFlag;
	}
	
}
