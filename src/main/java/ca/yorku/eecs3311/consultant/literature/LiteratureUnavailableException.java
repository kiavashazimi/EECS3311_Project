package ca.yorku.eecs3311.consultant.literature;

/**
 * Signals that a literature search could not be completed: the API failed,
 * timed out, or returned something unreadable.
 *
 * <p>This is flow 4a of UC03 — *"the Literature API fails or times out, the
 * error is shown with a retry action, and previously discovered papers remain
 * visible"*. Checked, like {@code LLMUnavailableException}, so a caller cannot
 * forget that the feed must survive a failed search.
 */
public class LiteratureUnavailableException extends Exception {

    private static final long serialVersionUID = 1L;

    public LiteratureUnavailableException(String reason) {
        super(reason);
    }

    public LiteratureUnavailableException(String reason, Throwable cause) {
        super(reason, cause);
    }
}
