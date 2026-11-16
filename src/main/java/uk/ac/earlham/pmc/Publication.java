package uk.ac.earlham.pmc;

import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The parts of a Europe PMC search result we return. Missing values are omitted from the JSON.
 *
 * @param id The Europe PMC ID of the publication.
 * @param source The Europe PMC source the publication comes from, e.g. MED or PMC.
 * @param pmid The PubMed ID.
 * @param pmcid The PubMed Central ID.
 * @param doi The publication's DOI.
 * @param title The title of the publication.
 * @param authorString The authors, as a single comma-separated String.
 * @param journal The title of the journal it was published in.
 * @param pubYear The year it was published.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Publication (
        String id,
        String source,
        String pmid,
        String pmcid,
        String doi,
        String title,
        String authorString,
        String journal,
        String pubYear) {

    /**
     * Builds a Publication from one entry of a Europe PMC resultList.result array.
     *
     * @param r The JSON for a single search result.
     * @return The new Publication. Any values missing from the JSON are <code>null</code>.
     */
    static Publication fromEuropePmc (JsonNode r) {
        return new Publication (
                text(r.path("id")),
                text(r.path("source")),
                text(r.path("pmid")),
                text(r.path("pmcid")),
                text(r.path("doi")),
                text(r.path("title")),
                text(r.path("authorString")),
                text(r.path("journalInfo").path("journal").path("title")),
                text(r.path("pubYear")));
    }

    /**
     * Get the value of a JSON node as a String.
     *
     * @param node The node to get the value of.
     * @return The node's value, or <code>null</code> if it's missing or isn't a
     * simple value such as a String or number.
     */
    private static String text (JsonNode node) {
        return node.isValueNode () ? node.asString () : null;
    }


    /**
     * Two Publications are equal if all of their fields are equal, where
     * any of the fields may be <code>null</code>.
     *
     * @param o The object to compare with.
     * @return <code>true</code> if the given object is an equal Publication,
     * <code>false</code> otherwise.
     */
    public boolean equals (Object o) {
    	boolean equalsFlag = false;
    	
    	if (o instanceof Publication) {
    		if (o == this) {
    			equalsFlag = true;
    		} else {
    			Publication p = (Publication) o;
    			
    			equalsFlag = (
    					/* Objects.equals () as any of these can be null */
    					Objects.equals (this.id, p.id) && Objects.equals (this.source, p.source) && 
    					Objects.equals (this.pmid, p.pmid) && Objects.equals (this.pmcid, p.pmcid) && 
    					Objects.equals (this.doi, p.doi) && Objects.equals (this.title, p.title) && 
    					Objects.equals (this.authorString, p.authorString) && Objects.equals (this.journal, p.journal) && 
    					Objects.equals (this.pubYear, p.pubYear));
    						
    			
    		}
    		
    	}
    		
    		
		return equalsFlag;
    }


    /**
     * Get this Publication as a schema.org ScholarlyArticle.
     *
     * @param mapper The ObjectMapper used to create the JSON.
     * @return The JSON representation of this Publication.
     */
    public ObjectNode getAsJSON (ObjectMapper mapper) {
    	ObjectNode thisNode = mapper.createObjectNode ();
    	
    	thisNode.put ("@type", "https://schema.org/ScholarlyArticle");
    	thisNode.put ("identifier", this.id);
    	thisNode.put ("name", this.title);
    	thisNode.put ("author", this.authorString);
    	thisNode.put ("publisher", this.source);
    	thisNode.put ("datePublished", this.pubYear);
    	thisNode.put ("sameAs", this.doi);
    	
    	
    	
    	return thisNode;    	
    }
}
