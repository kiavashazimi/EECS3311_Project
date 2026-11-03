package ca.yorku.eecs3311.consultant.repo;

/**
 * Thrown when a repository's metadata cannot be read: private, missing,
 * rate-limited, or not a recognisable repository URL — UC08 flow 3a, which
 * requires the reason to be reported rather than a bare failure.
 */
public class RepositoryUnavailableException extends Exception {

    private static final long serialVersionUID = 1L;

    public RepositoryUnavailableException(String reason) {
        super(reason);
    }

    public RepositoryUnavailableException(String reason, Throwable cause) {
        super(reason, cause);
    }
}
