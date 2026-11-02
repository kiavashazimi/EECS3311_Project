package ca.yorku.eecs3311.consultant.domain;

/**
 * The verdict F07 reaches about whether a paper's results can fairly be
 * compared with the researcher's own.
 *
 * <p>Deviation from the Stage 1 class diagram, which declared
 * {@code comparable: Boolean}. Two values cannot express the third outcome the
 * feature requires, and representing "cannot determine" as {@code null} would
 * reintroduce exactly the ambiguity F07 exists to remove.
 */
public enum Comparability {

    /** Same dataset, same metric, same split: the numbers may be placed side by side. */
    COMPARABLE,

    /** A field is present on both sides and differs. The comparison is invalid. */
    NOT_COMPARABLE,

    /**
     * A field needed for the decision is unknown on at least one side. Never
     * treated as a match or a mismatch.
     */
    CANNOT_DETERMINE;

    /** @return true only for {@link #COMPARABLE}; the other two are not "yes" */
    public boolean isComparable() {
        return this == COMPARABLE;
    }

    /** @return a short label for the GUI badge and the CLI output */
    public String label() {
        return switch (this) {
            case COMPARABLE -> "COMPARABLE";
            case NOT_COMPARABLE -> "NOT COMPARABLE";
            case CANNOT_DETERMINE -> "CANNOT DETERMINE";
        };
    }
}
