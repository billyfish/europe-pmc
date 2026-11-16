package uk.ac.earlham.pmc;

/**
 * Something that collects the errors and warnings raised while a search runs,
 * so that they can be reported back to the caller.
 */
public interface ErrorReceiver {

	/**
	 * Record an error or warning.
	 *
	 * @param level The severity of the problem.
	 * @param errorStr A description of the problem.
	 */
	void addError (System.Logger.Level level, String errorStr);
}
