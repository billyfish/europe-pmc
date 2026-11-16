package uk.ac.earlham.pmc;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rewrites institution names of the form "University of X" as "X University",
 * e.g. "University of Sheffield" becomes "Sheffield University".
 * An optional leading "The" is also accepted and dropped, so
 * "The University of Edinburgh" becomes "Edinburgh University".
 * {@link #toUniversityOf(String)} does the reverse, turning "X University"
 * into "University of X".
 * The match is case-insensitive and any leading/trailing whitespace is ignored.
 * <p>
 * Names whose wording is part of a proper name, such as "The Open University",
 * are listed as exceptions and returned unchanged by both methods. Matching
 * against the exceptions ignores case, extra whitespace and a leading "The".
 * More can be added with {@link #addException(String)}.
 */
public class InstitutionNameNormaliser {

	private static final Pattern UNIVERSITY_OF_PATTERN =
		Pattern.compile ("^\\s*(?:the\\s+)?university\\s+of\\s+(.+?)\\s*$", Pattern.CASE_INSENSITIVE);

	private static final Pattern X_UNIVERSITY_PATTERN =
		Pattern.compile ("^\\s*(?:the\\s+)?(.+?)\\s+university\\s*$", Pattern.CASE_INSENSITIVE);


	private static final Pattern WHITESPACE_PATTERN = Pattern.compile ("\\s+");

	private static final Pattern LEADING_THE_PATTERN = Pattern.compile ("^the\\s+");

	private static final Set <String> EXCEPTIONS = ConcurrentHashMap.newKeySet ();

	static {
		String [] defaults = {
			"Open University",
			"Anglia Ruskin University",
			"Nottingham Trent University",
			"Sheffield Hallam University",
			"Oxford Brookes University",
			"Manchester Metropolitan University",
			"Liverpool John Moores University",
			"Leeds Beckett University",
			"Edinburgh Napier University",
			"Glasgow Caledonian University",
			"Robert Gordon University",
			"Heriot-Watt University",
			"Queen Margaret University",
			"University of the Arts London",
			"University of the Highlands and Islands",
			"University of the West of England",
			"University of the West of Scotland"
		};

		for (String name : defaults) {
			addException (name);
		}
	}


	/**
	 * Not used, since this class only has static methods.
	 */
	private InstitutionNameNormaliser () {
	}


	/**
	 * Add a name that should be left unchanged by both conversions.
	 *
	 * @param name the institution name to leave alone.
	 */
	public static void addException (String name) {
		if (name != null) {
			EXCEPTIONS.add (toExceptionKey (name));
		}
	}


	/**
	 * Check whether a name is in the list of exceptions.
	 *
	 * @param name the institution name.
	 * @return true if the name should be left unchanged.
	 */
	public static boolean isException (String name) {
		return (name != null) && EXCEPTIONS.contains (toExceptionKey (name));
	}


	/**
	 * Get the form of a name used to look it up in the exceptions, i.e.
	 * lower case, with runs of whitespace collapsed and any leading "the" removed.
	 *
	 * @param name the institution name.
	 * @return the lookup key.
	 */
	private static String toExceptionKey (String name) {
		String key = WHITESPACE_PATTERN.matcher (name.trim ()).replaceAll (" ").toLowerCase (Locale.ROOT);
		return LEADING_THE_PATTERN.matcher (key).replaceFirst ("");
	}


	/**
	 * Get the other common form of an institution's name, so that "University of X"
	 * gives "X University" and "X University" gives "University of X".
	 *
	 * @param name the institution name.
	 * @return the alternative name, or the original value unchanged if it
	 * has no alternative, is an exception, or is null.
	 */
	public static String getAlternativeName (String name) {
		String s;
		
		if ((name != null) && (!isException (name))) {
			s = fromUniversityOf (name);
			
			if (s.equals (name)) {
				s = toUniversityOf (name);
			}
			
		} else {
			s = name;
		}
		
		return s;
	}

	/**
	 * Convert "University of X" into "X University".
	 *
	 * @param name the institution name.
	 * @return the rewritten name, or the original value unchanged if it
	 * does not begin with "university of", is an exception, or is null.
	 */
	public static String fromUniversityOf (String name) {
		if ((name == null) || isException (name)) {
			return name;
		}

		Matcher matcher = UNIVERSITY_OF_PATTERN.matcher (name);

		if (matcher.matches ()) {
			return matcher.group (1) + " University";
		}

		return name;
	}


	/**
	 * Convert "X University" into "University of X".
	 *
	 * @param name the institution name.
	 * @return the rewritten name, or the original value unchanged if it
	 * does not end with "university", is an exception, or is null.
	 */
	public static String toUniversityOf (String name) {
		if ((name == null) || isException (name)) {
			return name;
		}

		Matcher matcher = X_UNIVERSITY_PATTERN.matcher (name);

		if (matcher.matches ()) {
			return "University of " + matcher.group (1);
		}

		return name;
	}


	/**
	 * Print the conversions of some example names, for manual testing.
	 *
	 * @param args Not used.
	 */
	public static void main (String [] args) {
		String [] tests = {
			"University of East Anglia",
			"university of cambridge",
			"  UNIVERSITY OF   Oxford  ",
			"Earlham Institute",
			"The University of Edinburgh",
			"East Anglia University",
			"cambridge university",
			"  Oxford   UNIVERSITY  ",
			"The Edinburgh University",
			"The Open University",
			"open   university",
			"Sheffield Hallam University",
			"The University of the Highlands and Islands"
		};

		for (String test : tests) {
			System.out.println ("\"" + test + "\": normalise -> \"" + fromUniversityOf (test) + "\", toUniversityOf -> \"" + toUniversityOf (test) + "\"");
		}
	}
}
