package ca.yorku.eecs3311.consultant.domain;

/**
 * A discovered paper together with why it matters to this project.
 *
 * <p>The score alone is not the product. A researcher cannot act on "87" —
 * they need to know that a paper uses the same conditioning method on a
 * different modality, or that it measures a different fairness gap. {@link
 * #why()} is what makes the ranking usable, and {@link #closestPriorWork()}
 * marks the papers worth a full comparison under F06.
 *
 * @param paper the paper itself
 * @param score usefulness to this project, 0–100; 0 when unranked
 * @param why one line naming what transfers or what differs; blank when unranked
 * @param closestPriorWork whether this is among the nearest existing work
 */
public record RankedPaper(PaperRecord paper, int score, String why, boolean closestPriorWork) {

    public RankedPaper {
        if (paper == null) {
            throw new IllegalArgumentException("paper must not be null");
        }
        if (score < 0 || score > 100) {
            throw new IllegalArgumentException("score must be 0-100, was " + score);
        }
        why = why == null ? "" : why.strip();
    }

    /** A paper that arrived without a ranking, because the LLM was unavailable. */
    public static RankedPaper unranked(PaperRecord paper) {
        return new RankedPaper(paper, 0, "", false);
    }

    /** @return true when no ranking was applied, so the UI can label it */
    public boolean isUnranked() {
        return score == 0 && why.isEmpty();
    }
}
