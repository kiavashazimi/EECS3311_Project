package ca.yorku.eecs3311.consultant.agent;

/**
 * Thrown when an agent could not complete its run.
 *
 * <p>Agents work through tools that fail for reasons the researcher must see:
 * SD03 requires a failed literature search to reach the GUI as an error with a
 * Retry, with previously discovered papers still on screen. {@link Agent#run}
 * therefore has to be able to report failure, and this is what it reports.
 *
 * <p>Checked, for the same reason as {@code LLMUnavailableException}: a client
 * that forgets the error path should not compile.
 */
public class AgentException extends Exception {

    private static final long serialVersionUID = 1L;

    public AgentException(String reason) {
        super(reason);
    }

    public AgentException(String reason, Throwable cause) {
        super(reason, cause);
    }
}
